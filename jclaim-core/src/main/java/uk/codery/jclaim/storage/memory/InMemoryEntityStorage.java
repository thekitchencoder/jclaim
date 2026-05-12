package uk.codery.jclaim.storage.memory;

import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.storage.AliasAlreadyClaimedException;
import uk.codery.jclaim.storage.EntityNotFoundException;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.StorageOutcome;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * In-memory {@link EntityStorage} adapter backed by {@link ConcurrentHashMap}
 * indexes for URN, human ID, and alias lookups. Suitable for tests, demos and
 * single-process embeddings where durability is not required.
 *
 * <p>Mutating operations are serialised through a single {@link ReentrantLock}.
 * This trades a small amount of contention for an obviously-correct
 * concurrency story: alias uniqueness, human ID uniqueness, and URN
 * uniqueness are all checked under the same critical section as the
 * cross-index publication. Readers do not acquire the lock and see the
 * consistent state published when each writer releases.
 */
public final class InMemoryEntityStorage implements EntityStorage {

    private final ConcurrentHashMap<EntityId, Entity> byUrn = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EntityId> byHumanId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Alias, EntityId> byAlias = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();

    @Override
    public Optional<Entity> findByUrn(EntityId urn) {
        Objects.requireNonNull(urn, "urn");
        return Optional.ofNullable(byUrn.get(urn));
    }

    @Override
    public Optional<Entity> findByHumanId(String humanId) {
        Objects.requireNonNull(humanId, "humanId");
        EntityId urn = byHumanId.get(humanId);
        return urn == null ? Optional.empty() : Optional.ofNullable(byUrn.get(urn));
    }

    @Override
    public Optional<Entity> findByAlias(Alias alias) {
        Objects.requireNonNull(alias, "alias");
        EntityId urn = byAlias.get(alias);
        return urn == null ? Optional.empty() : Optional.ofNullable(byUrn.get(urn));
    }

    @Override
    public StorageOutcome resolveOrCreate(Alias alias, Supplier<Entity> mintFactory) {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(mintFactory, "mintFactory");

        writeLock.lock();
        try {
            EntityId existing = byAlias.get(alias);
            if (existing != null) {
                Entity entity = byUrn.get(existing);
                if (entity == null) {
                    throw new IllegalStateException(
                            "Alias index points at missing entity: " + existing);
                }
                return new StorageOutcome.Existing(entity);
            }

            Entity minted = mintFactory.get();
            Objects.requireNonNull(minted, "mintFactory returned null");
            if (!minted.aliases().contains(alias)) {
                throw new IllegalStateException(
                        "Minted entity must carry the claim alias " + alias
                                + "; carried " + minted.aliases());
            }
            if (byUrn.containsKey(minted.id())) {
                throw new IllegalStateException("URN collision on mint: " + minted.id());
            }
            if (byHumanId.containsKey(minted.humanId())) {
                throw new IllegalStateException(
                        "humanId collision on mint: " + minted.humanId());
            }
            for (Alias other : minted.aliases()) {
                if (!other.equals(alias) && byAlias.containsKey(other)) {
                    throw new AliasAlreadyClaimedException(other, byAlias.get(other));
                }
            }

            byUrn.put(minted.id(), minted);
            byHumanId.put(minted.humanId(), minted.id());
            for (Alias other : minted.aliases()) {
                byAlias.put(other, minted.id());
            }
            return new StorageOutcome.Created(minted);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Entity addAlias(EntityId urn, Alias alias) {
        Objects.requireNonNull(urn, "urn");
        Objects.requireNonNull(alias, "alias");

        writeLock.lock();
        try {
            Entity entity = byUrn.get(urn);
            if (entity == null) {
                throw new EntityNotFoundException(urn);
            }
            EntityId existingOwner = byAlias.get(alias);
            if (existingOwner != null) {
                if (existingOwner.equals(urn)) {
                    return entity;
                }
                throw new AliasAlreadyClaimedException(alias, existingOwner);
            }

            Entity updated = entity.withAlias(alias, entity.updatedAt());
            byUrn.put(urn, updated);
            byAlias.put(alias, urn);
            return updated;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Set<Entity> findCandidates(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        Alias claimAlias = claim.asAlias();
        Set<MatchingAttribute> claimAttrs = new HashSet<>(claim.attributes());

        Set<Entity> candidates = new LinkedHashSet<>();
        for (Entity entity : byUrn.values()) {
            if (entity.aliases().contains(claimAlias)) {
                candidates.add(entity);
                continue;
            }
            for (MatchingAttribute attr : entity.attributes()) {
                if (claimAttrs.contains(attr)) {
                    candidates.add(entity);
                    break;
                }
            }
        }
        return candidates;
    }

    /** Returns the current number of stored entities. Intended for tests. */
    public int size() {
        return byUrn.size();
    }
}
