package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;

/**
 * Wraps the auto-configured {@link EntityResolver} with a
 * {@link MeteredEntityResolver} when a {@link MeterRegistry} is on the
 * classpath and registered as a bean, and {@code jclaim.metrics.enabled}
 * is {@code true} (default). Registered as {@code @Primary} so injection
 * sites receive the metered wrapper without further qualifiers.
 */
@AutoConfiguration(after = JclaimAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "jclaim.metrics", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JclaimMetricsAutoConfiguration {

    @Bean
    @Primary
    public EntityResolver meteredEntityResolver(
            @Qualifier("jclaimEntityResolver") EntityResolver delegate,
            MeterRegistry registry) {
        return new MeteredEntityResolver(delegate, registry);
    }
}
