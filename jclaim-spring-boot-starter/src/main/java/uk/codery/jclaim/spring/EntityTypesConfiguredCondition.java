package uk.codery.jclaim.spring;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when at least one {@code jclaim.entity-types.<type>} entry is present,
 * i.e. the application runs in multi-type mode. Detection binds the keyed map
 * via {@link Binder} so relaxed-binding key forms are honoured (a raw
 * environment property lookup would miss them).
 */
public class EntityTypesConfiguredCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean present = Binder.get(context.getEnvironment())
                .bind("jclaim.entity-types", Bindable.mapOf(String.class, Object.class))
                .map(m -> !m.isEmpty())
                .orElse(false);
        return present
                ? ConditionOutcome.match("jclaim.entity-types entries are configured (multi-type mode)")
                : ConditionOutcome.noMatch("no jclaim.entity-types entries configured");
    }
}
