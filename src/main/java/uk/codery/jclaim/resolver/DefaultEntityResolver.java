package uk.codery.jclaim.resolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.codery.jclaim.event.AttributeDiff;
import uk.codery.jclaim.event.ConflictEventSink;
import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.id.HumanIdGenerator;
import uk.codery.jclaim.id.UuidV7;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.StorageOutcome;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Default {@link EntityResolver} implementation. Composes a storage adapter
 * with identifier generators, a clock, and a {@link ConflictEventSink}.
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
    private final Supplier<UUID> uuidSupplier;
    private final HumanIdGenerator humanIdGenerator;
    private final Clock clock;
    private final ConflictEventSink conflictSink;

    /** Builder for {@link DefaultEntityResolver}. */
    public static Builder builder(EntityStorage storage) {
        return new Builder(storage);
    }

    private DefaultEntityResolver(Builder b) {
        this.storage = Objects.requireNonNull(b.storage, "storage");
        this.namespace = Objects.requireNonNull(b.namespace, "namespace");
        this.uuidSupplier = Objects.requireNonNull(b.uuidSupplier, "uuidSupplier");
        this.humanIdGenerator = Objects.requireNonNull(b.humanIdGenerator, "humanIdGenerator");
        this.clock = Objects.requireNonNull(b.clock, "clock");
        this.conflictSink = Objects.requireNonNull(b.conflictSink, "conflictSink");
    }

    @Override
    public ResolutionResult resolveOrMint(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        Alias alias = claim.asAlias();

        StorageOutcome outcome = storage.resolveOrCreate(alias, () -> mintEntity(claim));

        return switch (outcome) {
            case StorageOutcome.Created created -> {
                log.debug("Minted {} for alias {}", created.entity().id(), alias);
                yield new ResolutionResult.Minted(created.entity());
            }
            case StorageOutcome.Existing existing -> {
                emitConflictIfDiverged(existing.entity(), claim);
                log.debug("Matched alias {} to {}", alias, existing.entity().id());
                yield new ResolutionResult.Matched(existing.entity());
            }
        };
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

    private Entity mintEntity(Claim claim) {
        Instant now = clock.instant();
        EntityId entityId = EntityId.of(namespace, uuidSupplier.get());
        String humanId = freshHumanId();
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
        try {
            conflictSink.accept(new EntityAttributesConflicted(
                    stored, incoming, diffs, clock.instant()));
        } catch (RuntimeException ex) {
            log.warn("ConflictEventSink threw while handling event for {}: {}",
                    stored.id(), ex.toString());
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
        // Then incoming-only attributes — these are conflicts because the
        // claim asserts a value the stored entity does not carry.
        for (Map.Entry<String, Object> entry : incomingByName.entrySet()) {
            if (!storedByName.containsKey(entry.getKey())) {
                diffs.add(new AttributeDiff(entry.getKey(), null, entry.getValue()));
            }
        }
        return diffs;
    }

    /** Fluent builder for {@link DefaultEntityResolver}. */
    public static final class Builder {
        private final EntityStorage storage;
        private String namespace = EntityId.DEFAULT_NAMESPACE;
        private Supplier<UUID> uuidSupplier = UuidV7.supplier();
        private HumanIdGenerator humanIdGenerator = new HumanIdGenerator();
        private Clock clock = Clock.systemUTC();
        private ConflictEventSink conflictSink = ConflictEventSink.noop();

        private Builder(EntityStorage storage) {
            this.storage = storage;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder uuidSupplier(Supplier<UUID> uuidSupplier) {
            this.uuidSupplier = uuidSupplier;
            return this;
        }

        public Builder humanIdGenerator(HumanIdGenerator humanIdGenerator) {
            this.humanIdGenerator = humanIdGenerator;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder conflictSink(ConflictEventSink conflictSink) {
            this.conflictSink = conflictSink;
            return this;
        }

        public DefaultEntityResolver build() {
            return new DefaultEntityResolver(this);
        }
    }
}
