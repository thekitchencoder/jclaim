package uk.codery.jclaim.resolver;

import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;

import java.util.Optional;

/**
 * Public surface of the JClaim library. Implementations resolve identity
 * claims to canonical {@link Entity entities}, minting new entities when no
 * existing alias matches.
 *
 * <p>Operations on this interface are deliberately limited to those required
 * for safe operation under concurrent ingest:
 *
 * <ul>
 *   <li>{@link #resolveOrMint(Claim)} — atomic match-or-mint.</li>
 *   <li>{@link #getByUrn(EntityId)} — strict lookup by URN.</li>
 *   <li>{@link #findByHumanId(String)} — Optional lookup by display ID.</li>
 *   <li>{@link #findByAlias(SourceSystem, String)} — Optional lookup by
 *       source alias.</li>
 *   <li>{@link #addAlias(EntityId, SourceSystem, String)} — atomic alias
 *       attachment with uniqueness enforcement.</li>
 * </ul>
 *
 * <p>Merge, split, and attribute-overwrite operations are deferred to later
 * modules.
 */
public interface EntityResolver {

    /**
     * Resolves {@code claim} against the alias index. Returns
     * {@link ResolutionResult.Matched} if the claim's
     * {@code (source, sourceId)} alias is already mapped, otherwise mints a
     * new canonical entity and returns {@link ResolutionResult.Minted}.
     *
     * <p>When matching, if the stored entity's attributes diverge from the
     * incoming claim, an {@link uk.codery.jclaim.event.EntityAttributesConflicted}
     * event is delivered to the resolver's configured conflict sink. The
     * stored entity is not modified.
     */
    ResolutionResult resolveOrMint(Claim claim);

    /** Strict lookup by URN; throws if the entity is unknown. */
    Entity getByUrn(EntityId urn);

    /** Lookup by human-friendly identifier. */
    Optional<Entity> findByHumanId(String humanId);

    /** Lookup by {@code (source, sourceId)} alias. */
    Optional<Entity> findByAlias(SourceSystem source, String sourceId);

    /** Atomically attaches {@code (source, sourceId)} as an alias of {@code urn}. */
    Entity addAlias(EntityId urn, SourceSystem source, String sourceId);

    /** Convenience overload for {@link #addAlias(EntityId, SourceSystem, String)}. */
    default Entity addAlias(EntityId urn, Alias alias) {
        return addAlias(urn, alias.source(), alias.sourceId());
    }
}
