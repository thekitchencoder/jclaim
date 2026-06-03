package uk.codery.jclaim.matching;

/**
 * Outcome of evaluating a {@link MatchingPolicy} for a single
 * {@code (Claim, candidate)} pair.
 *
 * <p>The matching layer is deliberately tri-state: a policy can assert that a
 * candidate <em>is</em> the same entity ({@link #MATCHED}), <em>is not</em> the
 * same entity ({@link #NOT_MATCHED}), or that it lacks the evidence to decide
 * either way ({@link #UNDETERMINED}). The resolver collapses these outcomes
 * across the candidate pool into a single canonical identity, surfacing
 * ambiguity ({@code UNDETERMINED} everywhere, or multiple {@code MATCHED}) as
 * typed stewardship events rather than silently guessing.
 */
public enum TriState {

    /** The candidate is the same entity the claim describes. */
    MATCHED,

    /** The candidate is definitively a different entity. */
    NOT_MATCHED,

    /** There is insufficient evidence to decide for or against the candidate. */
    UNDETERMINED
}
