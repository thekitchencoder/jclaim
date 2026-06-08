package uk.codery.jclaim.resolver;

import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;

import java.util.Optional;
import java.util.Set;

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
 *   <li>{@link #findByPublicId(String)} — Optional lookup by public identifier.</li>
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

    /** Lookup by public identifier. */
    Optional<Entity> findByPublicId(String publicId);

    /** Lookup by {@code (source, sourceId)} alias. */
    Optional<Entity> findByAlias(SourceSystem source, String sourceId);

    /** Atomically attaches {@code (source, sourceId)} as an alias of {@code urn}. */
    Entity addAlias(EntityId urn, SourceSystem source, String sourceId);

    /** Convenience overload for {@link #addAlias(EntityId, SourceSystem, String)}. */
    default Entity addAlias(EntityId urn, Alias alias) {
        return addAlias(urn, alias.source(), alias.sourceId());
    }

    /**
     * Returns the set of stored entities that could potentially be the same
     * entity {@code claim} describes — the union of entities sharing the
     * claim's alias and entities carrying any of the claim's {@code (name,
     * value)} attribute pairs.
     *
     * <p>This is an <strong>inspection</strong> API for stewardship,
     * debugging and analytics — "which entities could this claim be?". It is
     * deliberately not used by {@link #resolveOrMint(Claim)} in the current
     * release: the resolver matches purely on the alias index until a future
     * JSpec-driven matching policy plugs in. Callers that want to apply a
     * matching policy themselves can score and filter the candidates
     * returned here.
     *
     * <p>Returns an empty set if no candidates exist; never returns
     * {@code null}.
     */
    Set<Entity> findCandidates(Claim claim);
}
