package uk.codery.jclaim.spring.health;

import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import uk.codery.jclaim.spring.EntityTypesConfiguredCondition;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;
import uk.codery.jclaim.spring.NoEntityTypesCondition;
import uk.codery.jclaim.storage.EntityStorage;

/**
 * Registers JClaim Actuator health contributors. Active only when Spring Boot
 * Actuator is on the classpath and {@code jclaim.health.enabled} is {@code true}
 * (default).
 *
 * <p><b>Single-type mode</b> ({@link NoEntityTypesCondition}): a single
 * {@code jclaimHealthIndicator} probes the shared {@link EntityStorage} bean.
 * It is further gated on that bean being present.
 *
 * <p><b>Multi-type mode</b> ({@link EntityTypesConfiguredCondition},
 * {@code jclaim.entity-types.*}): the single-type indicator backs off (it would
 * otherwise face an ambiguous {@link EntityStorage} autowire across the per-type
 * storage beans). Instead a {@link PerTypeHealthIndicatorRegistrar} registers one
 * {@code jclaimHealthIndicator_<type>} contributor per type, each probing that
 * type's scoped {@code jclaimEntityStorage_<type>} bean.
 */
@AutoConfiguration(after = JclaimAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "jclaim.health", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JclaimHealthAutoConfiguration {

    @Bean(name = "jclaimHealthIndicator")
    @Conditional(NoEntityTypesCondition.class)
    @ConditionalOnBean(EntityStorage.class)
    @ConditionalOnMissingBean(name = "jclaimHealthIndicator")
    public HealthIndicator jclaimHealthIndicator(EntityStorage storage) {
        return new JclaimHealthIndicator(storage);
    }

    /**
     * Multi-type: one health contributor per entity type. Must be {@code static}
     * so the {@link org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor}
     * runs early (after the per-type storage definitions are registered) without
     * prematurely initialising the enclosing configuration.
     */
    @Bean
    @Conditional(EntityTypesConfiguredCondition.class)
    static PerTypeHealthIndicatorRegistrar jclaimPerTypeHealthIndicatorRegistrar() {
        return new PerTypeHealthIndicatorRegistrar();
    }
}
