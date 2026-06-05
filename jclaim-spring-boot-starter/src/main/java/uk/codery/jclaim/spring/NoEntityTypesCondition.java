package uk.codery.jclaim.spring;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Negation of {@link EntityTypesConfiguredCondition}: matches when no
 * {@code jclaim.entity-types.<type>} entries are configured, i.e. the
 * application runs in single (default) entity-type mode. Guards the single
 * top-level resolver so it is suppressed once multi-type entries appear. The
 * presence check is shared via {@link EntityTypesConditionSupport}.
 */
public class NoEntityTypesCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return EntityTypesConditionSupport.entityTypesPresent(context)
                ? ConditionOutcome.noMatch("jclaim.entity-types entries are configured (multi-type mode)")
                : ConditionOutcome.match("no jclaim.entity-types entries configured (single-type mode)");
    }
}
