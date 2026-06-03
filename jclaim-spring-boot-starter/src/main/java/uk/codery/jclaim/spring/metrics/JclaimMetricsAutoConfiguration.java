package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;

/**
 * Wraps the auto-configured {@link EntityResolver} with a
 * {@link MeteredEntityResolver}, and the auto-configured {@link MatchEventSink}
 * with a {@link MeteredMatchEventSink}, when a {@link MeterRegistry} is on the
 * classpath and registered as a bean, and {@code jclaim.metrics.enabled}
 * is {@code true} (default). Both decorators are registered as {@code @Primary}
 * so injection sites (including the resolver, which injects the sink) receive the
 * metered wrappers without further qualifiers.
 */
@AutoConfiguration(after = JclaimAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(value = MeterRegistry.class, name = "jclaimResolver")
@ConditionalOnProperty(prefix = "jclaim.metrics", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JclaimMetricsAutoConfiguration {

    @Bean
    @Primary
    public EntityResolver meteredEntityResolver(
            @Qualifier("jclaimResolver") EntityResolver delegate,
            MeterRegistry registry) {
        return new MeteredEntityResolver(delegate, registry);
    }

    @Bean
    @Primary
    public MatchEventSink meteredMatchEventSink(
            @Qualifier("jclaimMatchEventSink") MatchEventSink delegate,
            MeterRegistry registry) {
        return new MeteredMatchEventSink(delegate, registry);
    }
}
