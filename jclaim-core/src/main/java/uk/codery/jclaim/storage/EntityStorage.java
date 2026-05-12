package uk.codery.jclaim.storage;

import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Storage port used by the resolver. Adapters persist canonical entities
 * indexed by URN, human ID and alias, and must enforce alias uniqueness
 * atomically — naïve read-then-write implementations are incorrect under
 * concurrency.
 *
 * <p>The port surface is deliberately small. {@link #resolveOrCreate} is the
 * single atomic primitive that lets the resolver express "give me the entity
 * for this alias, minting one if needed" without a race window; the same
 * primitive maps cleanly to a MongoDB {@code findOneAndUpdate(..., upsert)}
 * call guarded by a unique compound index on {@code (source, sourceId)}.
 */
public interface EntityStorage {

    /** Lookup by canonical URN. */
    Optional<Entity> findByUrn(EntityId urn);

    /** Lookup by human-friendly identifier. */
    Optional<Entity> findByHumanId(String humanId);

    /** Lookup by {@code (source, sourceId)} alias. */
    Optional<Entity> findByAlias(Alias alias);

    /**
     * Atomically resolves the alias to an existing entity, or mints a new
     * entity using {@code mintFactory} and stores it under that alias.
     *
     * <p>The supplied factory must produce an entity whose alias list already
     * includes {@code alias}; the adapter rejects mismatched mint factories.
     * The factory may be invoked at most once per call. The entity URN and
     * human ID it produces must not collide with any already stored — the
     * caller is responsible for re-rolling on collision.
     */
    StorageOutcome resolveOrCreate(Alias alias, Supplier<Entity> mintFactory);

    /**
     * Adds {@code alias} to the entity identified by {@code urn}, returning
     * the updated entity. Throws {@link AliasAlreadyClaimedException} if the
     * alias is already mapped to a different entity, and
     * {@link EntityNotFoundException} if {@code urn} is unknown. If the
     * entity already carries the alias the call is a no-op and returns the
     * current state.
     */
    Entity addAlias(EntityId urn, Alias alias);

    /**
     * Returns the set of stored entities that could potentially be the same
     * entity the claim describes — the union of:
     *
     * <ul>
     *   <li>entities whose alias graph contains {@code (claim.source, claim.sourceId)},</li>
     *   <li>entities carrying any {@code (name, value)} attribute pair that also
     *       appears in {@code claim.attributes()}.</li>
     * </ul>
     *
     * <p>This is a <strong>candidate retrieval</strong> operation: it is
     * deliberately inclusive and performs no scoring, ranking or confidence
     * filtering. Callers — typically a future matching policy backed by JSpec —
     * score the returned set themselves and decide which (if any) candidate is
     * truly the same entity. {@link #resolveOrCreate} remains the canonical
     * decision-making entry point for the current alias-only matching model.
     *
     * <p>Equality of attribute values uses {@link Object#equals(Object)} on the
     * stored vs. claimed values. An entity with attribute {@code (name=email,
     * value="x")} is returned for a claim asserting attribute {@code
     * (name=email, value="x")}; it is <em>not</em> returned for a claim asserting
     * {@code (name=primary_email, value="x")} — both name and value must match.
     *
     * <p>Returns an empty set if no candidates exist; never returns
     * {@code null}.
     */
    Set<Entity> findCandidates(Claim claim);
}
