package uk.codery.jclaim.resolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.codery.jclaim.event.AttributeDiff;
import uk.codery.jclaim.event.CandidateOutcome;
import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.event.MatchAmbiguous;
import uk.codery.jclaim.event.MatchEvent;
import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.event.MatchUndecided;
import uk.codery.jclaim.id.HumanIdFormat;
import uk.codery.jclaim.id.HumanIdGenerator;
import uk.codery.jclaim.id.UuidV7;
import uk.codery.jclaim.matching.MatchingPolicy;
import uk.codery.jclaim.matching.TriState;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.storage.AliasAlreadyClaimedException;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.StorageOutcome;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Default {@link EntityResolver} implementation. Composes a storage adapter
 * with identifier generators, a clock, and a {@link MatchEventSink}.
 *
 * <p>Match semantics in this release are <strong>alias-only</strong>: a claim
 * matches an existing entity iff the storage adapter already records the
 * claim's {@code (source, sourceId)} pair. Attribute-based matching arrives
 * with the JSpec-backed matching policy in a later module.
 */
public final class DefaultEntityResolver implements EntityResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultEntityResolver.class);
    private static final int HUMAN_ID_MAX_ATTEMPTS = 16;

    private final EntityStorage storage;
    private final String namespace;
    private final String entityType;
    private final Supplier<UUID> uuidSupplier;
    private final HumanIdGenerator humanIdGenerator;
    private final Clock clock;
    private final MatchEventSink matchEventSink;
    private final MatchingPolicy matchingPolicy;
    private final int maxCandidates;

    /** Builder for {@link DefaultEntityResolver}. */
    public static Builder builder(EntityStorage storage) {
        return new Builder(storage);
    }

    private DefaultEntityResolver(Builder b) {
        this.storage = Objects.requireNonNull(b.storage, "storage");
        this.namespace = Objects.requireNonNull(b.namespace, "namespace");
        this.entityType = Objects.requireNonNull(b.entityType, "entityType");
        this.uuidSupplier = Objects.requireNonNull(b.uuidSupplier, "uuidSupplier");
        this.humanIdGenerator = b.humanIdGenerator;
        this.clock = Objects.requireNonNull(b.clock, "clock");
        this.matchEventSink = Objects.requireNonNull(b.matchEventSink, "matchEventSink");
        this.matchingPolicy = Objects.requireNonNull(b.matchingPolicy, "matchingPolicy");
        this.maxCandidates = b.maxCandidates;
    }

    @Override
    public ResolutionResult resolveOrMint(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        Alias alias = claim.asAlias();

        // 1. Exact-alias short-circuit: an alias owner is an identity link.
        // This preserves the alias-atomic concurrency guarantee — the policy
        // never runs against a candidate that already owns the claim's alias.
        Optional<Entity> owner = storage.findByAlias(alias);
        if (owner.isPresent()) {
            Entity matched = owner.get();
            emitConflictIfDiverged(matched, claim);
            log.debug("Matched alias {} to {} (exact-alias)", alias, matched.id());
            return new ResolutionResult.Matched(matched);
        }

        // 2. No exact alias owner: attribute blocking + matching policy.
        Set<Entity> candidates = storage.findCandidates(claim, maxCandidates);
        // Heuristic truncation flag: a full pool is *assumed* truncated. An exact
        // found-count is deferred — querying limit+1 to detect overflow would
        // defeat the cap's IO bound. candidatesFound therefore reports the
        // post-cap count (== candidatesConsidered).
        boolean truncated = candidates.size() == maxCandidates;
        if (truncated) {
            log.warn("Candidate pool for alias {} hit the cap of {}; results truncated",
                    alias, maxCandidates);
        }

        List<CandidateOutcome> outcomes = candidates.stream()
                .map(c -> new CandidateOutcome(c, matchingPolicy.evaluate(claim, c)))
                .toList();
        List<CandidateOutcome> matched = outcomes.stream()
                .filter(o -> o.policyResult() == TriState.MATCHED)
                .toList();
        int considered = candidates.size();
        int found = candidates.size();

        if (matched.size() == 1) {
            return linkSingleMatch(claim, alias, matched.get(0).candidate());
        }
        if (matched.size() > 1) {
            return linkAmbiguousMatch(
                    claim, alias, matched, outcomes, considered, found, truncated);
        }

        // 3. No MATCHED candidate: atomic mint preserves the concurrency contract.
        StorageOutcome outcome = storage.resolveOrCreate(alias, () -> mintEntity(claim));
        return switch (outcome) {
            case StorageOutcome.Created created -> {
                Entity minted = created.entity();
                log.debug("Minted {} for alias {}", minted.id(), alias);
                if (outcomes.stream().anyMatch(o -> o.policyResult() == TriState.UNDETERMINED)) {
                    safeAccept(new MatchUndecided(
                            claim, minted, outcomes, considered, found, truncated), minted.id());
                }
                yield new ResolutionResult.Minted(minted);
            }
            case StorageOutcome.Existing existing -> {
                // Lost a mint race: the alias now resolves, so treat as a match.
                // No MatchUndecided — the alias resolves cleanly now.
                Entity now = existing.entity();
                emitConflictIfDiverged(now, claim);
                log.debug("Matched alias {} to {} (mint race lost)", alias, now.id());
                yield new ResolutionResult.Matched(now);
            }
        };
    }

    private ResolutionResult linkSingleMatch(Claim claim, Alias alias, Entity winner) {
        Entity attached;
        try {
            attached = storage.addAlias(winner.id(), alias);
        } catch (AliasAlreadyClaimedException e) {
            // A concurrent mint grabbed this alias between findCandidates and
            // addAlias; the alias now resolves to that entity.
            Entity now = storage.findByAlias(alias).orElseThrow();
            emitConflictIfDiverged(now, claim);
            log.debug("Matched alias {} to {} (alias claimed concurrently)", alias, now.id());
            return new ResolutionResult.Matched(now);
        }
        emitConflictIfDiverged(attached, claim);
        log.debug("Matched alias {} to {} (single policy match)", alias, attached.id());
        return new ResolutionResult.Matched(attached);
    }

    private ResolutionResult linkAmbiguousMatch(
            Claim claim, Alias alias, List<CandidateOutcome> matched,
            List<CandidateOutcome> outcomes, int considered, int found, boolean truncated) {
        Entity winner = matched.stream()
                .map(CandidateOutcome::candidate)
                .min(Comparator.comparing(Entity::createdAt)
                        .thenComparing(e -> e.id().urn()))
                .orElseThrow();
        List<Entity> others = matched.stream()
                .map(CandidateOutcome::candidate)
                .filter(e -> !e.equals(winner))
                .toList();

        Entity attached;
        try {
            attached = storage.addAlias(winner.id(), alias);
        } catch (AliasAlreadyClaimedException e) {
            Entity now = storage.findByAlias(alias).orElseThrow();
            emitConflictIfDiverged(now, claim);
            log.debug("Matched alias {} to {} (alias claimed concurrently)", alias, now.id());
            return new ResolutionResult.Matched(now);
        }
        safeAccept(new MatchAmbiguous(
                claim, attached, others, outcomes, considered, found, truncated), attached.id());
        emitConflictIfDiverged(attached, claim);
        log.debug("Matched alias {} to {} (ambiguous policy match, {} runners-up)",
                alias, attached.id(), others.size());
        return new ResolutionResult.Matched(attached);
    }

    @Override
    public Entity getByUrn(EntityId urn) {
        Objects.requireNonNull(urn, "urn");
        return storage.findByUrn(urn).orElseThrow(
                () -> new NoSuchElementException("No entity stored for URN " + urn));
    }

    @Override
    public Optional<Entity> findByHumanId(String humanId) {
        Objects.requireNonNull(humanId, "humanId");
        return storage.findByHumanId(humanId);
    }

    @Override
    public Optional<Entity> findByAlias(SourceSystem source, String sourceId) {
        return storage.findByAlias(new Alias(source, sourceId));
    }

    @Override
    public Entity addAlias(EntityId urn, SourceSystem source, String sourceId) {
        return storage.addAlias(urn, new Alias(source, sourceId));
    }

    @Override
    public Set<Entity> findCandidates(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return storage.findCandidates(claim);
    }

    private Entity mintEntity(Claim claim) {
        Instant now = clock.instant();
        EntityId entityId = EntityId.of(namespace, entityType, uuidSupplier.get());
        String humanId = humanIdGenerator == null ? null : freshHumanId();
        return new Entity(
                entityId,
                humanId,
                List.of(claim.asAlias()),
                claim.attributes(),
                null,
                now,
                now
        );
    }

    private String freshHumanId() {
        for (int attempt = 0; attempt < HUMAN_ID_MAX_ATTEMPTS; attempt++) {
            String candidate = humanIdGenerator.generate();
            if (storage.findByHumanId(candidate).isEmpty()) {
                return candidate;
            }
            log.warn("Human ID collision on attempt {} ({}); re-rolling", attempt + 1, candidate);
        }
        throw new IllegalStateException(
                "Failed to mint a unique human ID after " + HUMAN_ID_MAX_ATTEMPTS + " attempts");
    }

    private void emitConflictIfDiverged(Entity stored, Claim incoming) {
        List<AttributeDiff> diffs = diff(stored.attributes(), incoming.attributes());
        if (diffs.isEmpty()) {
            return;
        }
        safeAccept(new EntityAttributesConflicted(stored, incoming, diffs), stored.id());
    }

    /**
     * Delivers {@code event} to the configured sink, catching and WARN-logging
     * any sink {@link RuntimeException} so a misbehaving sink cannot break
     * resolution. {@code entityId} names the entity for the log line.
     */
    private void safeAccept(MatchEvent event, EntityId entityId) {
        try {
            matchEventSink.accept(event);
        } catch (RuntimeException ex) {
            log.warn("MatchEventSink threw while handling event for {}: {}",
                    entityId, ex.toString());
        }
    }

    private static List<AttributeDiff> diff(
            List<MatchingAttribute> stored, List<MatchingAttribute> incoming) {
        Map<String, Object> storedByName = new LinkedHashMap<>();
        for (MatchingAttribute a : stored) {
            storedByName.put(a.name(), a.value());
        }
        Map<String, Object> incomingByName = new LinkedHashMap<>();
        for (MatchingAttribute a : incoming) {
            incomingByName.put(a.name(), a.value());
        }

        List<AttributeDiff> diffs = new ArrayList<>();
        // Walk stored attributes first to preserve a deterministic ordering
        for (Map.Entry<String, Object> entry : storedByName.entrySet()) {
            String name = entry.getKey();
            Object storedValue = entry.getValue();
            if (!incomingByName.containsKey(name)) {
                continue; // claim doesn't assert this attribute; not a conflict
            }
            Object incomingValue = incomingByName.get(name);
            if (!Objects.equals(storedValue, incomingValue)) {
                diffs.add(new AttributeDiff(name, storedValue, incomingValue));
            }
        }
        // Attributes the claim introduces that the stored entity has never
        // carried are additive, not conflicts — they are intentionally omitted.
        return diffs;
    }

    /** Fluent builder for {@link DefaultEntityResolver}. */
    public static final class Builder {
        private final EntityStorage storage;
        private String namespace = EntityId.DEFAULT_NAMESPACE;
        private String entityType = EntityId.DEFAULT_TYPE;
        private Supplier<UUID> uuidSupplier = UuidV7.supplier();
        private HumanIdGenerator humanIdGenerator = null;
        private Clock clock = Clock.systemUTC();
        private MatchEventSink matchEventSink = MatchEventSink.noop();
        private MatchingPolicy matchingPolicy = MatchingPolicy.aliasOnly();
        private int maxCandidates = 100;

        private Builder(EntityStorage storage) {
            this.storage = storage;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /** Sets the URN type segment of minted entity IDs. Defaults to {@code entity}. */
        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }

        public Builder uuidSupplier(Supplier<UUID> uuidSupplier) {
            this.uuidSupplier = uuidSupplier;
            return this;
        }

        /**
         * Advanced/test hook: sets the humanId generator directly (e.g. with
         * deterministic entropy). {@code null} means this resolver mints no
         * humanId. Prefer {@link #humanIdTemplate(String)} for normal use; both
         * target the same field, so the last call wins.
         */
        public Builder humanIdGenerator(HumanIdGenerator humanIdGenerator) {
            this.humanIdGenerator = humanIdGenerator;
            return this;
        }

        /** Sets the humanId template. {@code null} or blank means this resolver mints no humanId. */
        public Builder humanIdTemplate(String template) {
            this.humanIdGenerator = (template == null || template.isBlank())
                    ? null
                    : new HumanIdGenerator(HumanIdFormat.ofTemplate(template));
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder matchEventSink(MatchEventSink matchEventSink) {
            this.matchEventSink = matchEventSink;
            return this;
        }

        /**
         * The matching policy used to score attribute-blocked candidates when
         * no exact alias owner exists. Defaults to {@link MatchingPolicy#aliasOnly()},
         * which reproduces jclaim's historic alias-only behaviour.
         */
        public Builder matchingPolicy(MatchingPolicy matchingPolicy) {
            this.matchingPolicy = matchingPolicy;
            return this;
        }

        /**
         * Caps how many candidates the resolver pulls from storage per claim.
         * Defaults to {@code 100}. Must be strictly positive.
         */
        public Builder maxCandidates(int maxCandidates) {
            if (maxCandidates <= 0) {
                throw new IllegalArgumentException(
                        "maxCandidates must be positive, was " + maxCandidates);
            }
            this.maxCandidates = maxCandidates;
            return this;
        }

        public DefaultEntityResolver build() {
            EntityId.requireValidSegment("namespace", namespace);
            EntityId.requireValidSegment("type", entityType);
            return new DefaultEntityResolver(this);
        }
    }
}
