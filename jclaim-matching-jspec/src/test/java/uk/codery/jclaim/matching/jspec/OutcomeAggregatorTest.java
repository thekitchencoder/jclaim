package uk.codery.jclaim.matching.jspec;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.matching.TriState;
import uk.codery.jspec.evaluator.SpecificationEvaluator;
import uk.codery.jspec.model.QueryCriterion;
import uk.codery.jspec.model.Specification;
import uk.codery.jspec.result.EvaluationOutcome;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the conjunctive aggregator against {@link EvaluationOutcome}s built
 * from real tiny specs, so the rule is tested against jspec's actual summary
 * semantics rather than a hand-rolled stub.
 */
class OutcomeAggregatorTest {

    /**
     * Evaluates a spec against the given document. Equality criteria match;
     * {@code $exists:false} on a present field does not match; a
     * {@code $contextPath} pointing at a missing context path is undetermined.
     */
    private static EvaluationOutcome evaluate(Specification spec, Map<String, Object> target) {
        // Empty context: $contextPath operands resolve to missing -> UNDETERMINED.
        return new SpecificationEvaluator(spec).evaluate(target, Map.of());
    }

    private static QueryCriterion match() {
        return new QueryCriterion("m", Map.of("x", Map.of("$eq", 1)));
    }

    private static QueryCriterion notMatch() {
        return new QueryCriterion("nm", Map.of("x", Map.of("$eq", 999)));
    }

    private static QueryCriterion undetermined() {
        // operand resolves against an absent context path -> UNDETERMINED
        return new QueryCriterion("u", Map.of("x", Map.of("$eq", Map.of("$contextPath", "candidate.missing"))));
    }

    @Test
    void allCriteriaMatchedAggregatesToMatched() {
        EvaluationOutcome outcome = evaluate(
                new Specification("s", List.of(match(), match())), Map.of("x", 1));

        assertThat(OutcomeAggregator.conjunctive().aggregate(outcome)).isEqualTo(TriState.MATCHED);
    }

    @Test
    void anyNotMatchedDominatesEvenAlongsideUndetermined() {
        EvaluationOutcome outcome = evaluate(
                new Specification("s", List.of(notMatch(), undetermined())), Map.of("x", 1));

        assertThat(outcome.summary().notMatched()).isPositive();
        assertThat(outcome.summary().undetermined()).isPositive();
        assertThat(OutcomeAggregator.conjunctive().aggregate(outcome)).isEqualTo(TriState.NOT_MATCHED);
    }

    @Test
    void undeterminedWithoutNotMatchedAggregatesToUndetermined() {
        EvaluationOutcome outcome = evaluate(
                new Specification("s", List.of(match(), undetermined())), Map.of("x", 1));

        assertThat(outcome.summary().notMatched()).isZero();
        assertThat(outcome.summary().undetermined()).isPositive();
        assertThat(OutcomeAggregator.conjunctive().aggregate(outcome)).isEqualTo(TriState.UNDETERMINED);
    }

    @Test
    void emptySpecWithZeroCriteriaAggregatesToUndetermined() {
        EvaluationOutcome outcome = evaluate(new Specification("empty", List.of()), Map.of("x", 1));

        assertThat(outcome.summary().total()).isZero();
        assertThat(OutcomeAggregator.conjunctive().aggregate(outcome)).isEqualTo(TriState.UNDETERMINED);
    }
}
