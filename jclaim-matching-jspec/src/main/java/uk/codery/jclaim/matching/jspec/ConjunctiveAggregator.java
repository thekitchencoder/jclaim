package uk.codery.jclaim.matching.jspec;

import uk.codery.jclaim.matching.TriState;
import uk.codery.jspec.result.EvaluationOutcome;
import uk.codery.jspec.result.EvaluationSummary;

/**
 * Stateless conjunctive {@link OutcomeAggregator}. See
 * {@link OutcomeAggregator#conjunctive()} for the rule.
 */
final class ConjunctiveAggregator implements OutcomeAggregator {

    static final ConjunctiveAggregator INSTANCE = new ConjunctiveAggregator();

    private ConjunctiveAggregator() {}

    @Override
    public TriState aggregate(EvaluationOutcome outcome) {
        EvaluationSummary summary = outcome.summary();
        if (summary.total() == 0) {
            return TriState.UNDETERMINED;
        }
        if (summary.notMatched() > 0) {
            return TriState.NOT_MATCHED;
        }
        if (summary.undetermined() > 0) {
            return TriState.UNDETERMINED;
        }
        return TriState.MATCHED;
    }
}
