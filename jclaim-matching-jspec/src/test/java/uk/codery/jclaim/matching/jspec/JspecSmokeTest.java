package uk.codery.jclaim.matching.jspec;

import org.junit.jupiter.api.Test;
import uk.codery.jspec.evaluator.SpecificationEvaluator;
import uk.codery.jspec.model.QueryCriterion;
import uk.codery.jspec.model.Specification;
import uk.codery.jspec.result.EvaluationOutcome;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canary test pinning the jspec 0.6.0 integration. Builds a {@link Specification}
 * with a {@code $contextPath} operand and asserts the two-arg
 * {@link SpecificationEvaluator#evaluate(Object, Object)} resolves the operand
 * against the context document and reports a single matched criterion.
 */
class JspecSmokeTest {

    @Test
    void contextPathOperandMatchesAgainstContextDocument() {
        Specification spec = new Specification("smoke", List.of(
            new QueryCriterion("same-email",
                Map.of("email", Map.of("$eq", Map.of("$contextPath", "candidate.email"))))));

        EvaluationOutcome outcome = new SpecificationEvaluator(spec)
            .evaluate(Map.of("email", "a@b.com"),
                      Map.of("candidate", Map.of("email", "a@b.com")));

        assertThat(outcome.summary().matched()).isEqualTo(1);
    }
}
