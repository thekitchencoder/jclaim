package uk.codery.jclaim.matching.jspec;

import uk.codery.jclaim.matching.TriState;
import uk.codery.jspec.result.EvaluationOutcome;

/**
 * Collapses a jspec {@link EvaluationOutcome} — the per-criterion result of
 * evaluating a matching spec for one {@code (Claim, candidate)} pair — into a
 * single {@link TriState}.
 *
 * <p>Implementations must be stateless and thread-safe.
 */
public interface OutcomeAggregator {

    /** Reduces {@code outcome} to a single tri-state verdict. */
    TriState aggregate(EvaluationOutcome outcome);

    /**
     * The default <em>conjunctive</em> aggregator: a candidate is
     * {@code MATCHED} only when every criterion matched. Over
     * {@link EvaluationOutcome#summary()}: any not-matched criterion yields
     * {@code NOT_MATCHED}; otherwise any undetermined criterion yields
     * {@code UNDETERMINED}; otherwise {@code MATCHED}. A spec with zero criteria
     * carries no evidence and so yields {@code UNDETERMINED}.
     *
     * <p>The returned instance is a stateless shared singleton.
     */
    static OutcomeAggregator conjunctive() {
        return ConjunctiveAggregator.INSTANCE;
    }
}
