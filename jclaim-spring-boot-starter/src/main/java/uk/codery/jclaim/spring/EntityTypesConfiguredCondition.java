package uk.codery.jclaim.spring;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when at least one {@code jclaim.entity-types.<type>} entry is present,
 * i.e. the application runs in multi-type mode. The presence check is shared with
 * {@link NoEntityTypesCondition} via {@link EntityTypesConditionSupport}.
 */
public class EntityTypesConfiguredCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return EntityTypesConditionSupport.entityTypesPresent(context)
                ? ConditionOutcome.match("jclaim.entity-types entries are configured (multi-type mode)")
                : ConditionOutcome.noMatch("no jclaim.entity-types entries configured");
    }
}
