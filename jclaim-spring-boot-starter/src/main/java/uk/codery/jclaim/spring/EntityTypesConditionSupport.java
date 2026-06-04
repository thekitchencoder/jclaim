package uk.codery.jclaim.spring;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;

/**
 * Shared {@code jclaim.entity-types}-present check used by both
 * {@link EntityTypesConfiguredCondition} and {@link NoEntityTypesCondition}.
 * Detection binds the keyed map via {@link Binder} so relaxed-binding key forms
 * are honoured (a raw environment property lookup would miss them).
 */
final class EntityTypesConditionSupport {

    private EntityTypesConditionSupport() {
    }

    /** Returns {@code true} when at least one {@code jclaim.entity-types.<type>} entry is present. */
    static boolean entityTypesPresent(ConditionContext context) {
        return Binder.get(context.getEnvironment())
                .bind("jclaim.entity-types", Bindable.mapOf(String.class, Object.class))
                .map(m -> !m.isEmpty())
                .orElse(false);
    }
}
