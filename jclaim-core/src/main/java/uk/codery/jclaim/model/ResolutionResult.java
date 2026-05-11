package uk.codery.jclaim.model;

/**
 * Outcome of {@code resolveOrMint(Claim)} — either the incoming claim
 * {@linkplain Matched matched} an entity already known via the
 * {@code (source, sourceId)} alias, or the resolver
 * {@linkplain Minted minted} a new canonical entity.
 *
 * <p>Sealed so callers can exhaustively switch on the two outcomes.
 */
public sealed interface ResolutionResult permits ResolutionResult.Matched, ResolutionResult.Minted {

    /** The reconciled entity associated with the claim. */
    Entity entity();

    /** Claim matched a stored entity via its {@code (source, sourceId)} alias. */
    record Matched(Entity entity) implements ResolutionResult {
        public Matched {
            if (entity == null) {
                throw new NullPointerException("entity");
            }
        }
    }

    /** No existing alias matched; the resolver created a new entity. */
    record Minted(Entity entity) implements ResolutionResult {
        public Minted {
            if (entity == null) {
                throw new NullPointerException("entity");
            }
        }
    }
}
