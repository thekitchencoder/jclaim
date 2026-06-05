package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import uk.codery.jclaim.spring.EntityTypesConfiguredCondition;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;

/**
 * Multi-type metrics: registers a {@link MultiTypeMetricsBeanPostProcessor}
 * that decorates each per-type resolver bean with a type-tagged
 * {@link MeteredEntityResolver}. Active only in multi-type mode
 * ({@code jclaim.entity-types.*} present) when a {@link MeterRegistry} is on
 * the classpath and {@code jclaim.metrics.enabled} is {@code true} (default).
 *
 * <p>Deliberately separate from {@link JclaimMetricsAutoConfiguration}: the
 * single-type config registers a {@code @Primary} metered resolver gated on the
 * {@code jclaimResolver} bean, which is absent in multi-type mode. Here there is
 * <em>no</em> {@code @Primary} resolver — selection stays via the type qualifier
 * or the {@link uk.codery.jclaim.resolver.EntityResolvers} facade.
 */
@AutoConfiguration(after = JclaimAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@Conditional(EntityTypesConfiguredCondition.class)
@ConditionalOnProperty(prefix = "jclaim.metrics", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JclaimMultiTypeMetricsAutoConfiguration {

    @Bean
    public static MultiTypeMetricsBeanPostProcessor jclaimMultiTypeMetricsBeanPostProcessor(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new MultiTypeMetricsBeanPostProcessor(meterRegistry);
    }
}
