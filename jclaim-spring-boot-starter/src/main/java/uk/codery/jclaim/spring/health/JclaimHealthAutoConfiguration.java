package uk.codery.jclaim.spring.health;

import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;
import uk.codery.jclaim.storage.EntityStorage;

/**
 * Registers a {@link JclaimHealthIndicator} that probes the configured
 * {@link EntityStorage}. Active only when Spring Boot Actuator is on the
 * classpath and {@code jclaim.health.enabled} is {@code true} (default).
 *
 * <p>The indicator bean is further gated on a single {@link EntityStorage}
 * bean being present. In multi-type mode ({@code jclaim.entity-types.*}) the
 * single-type storage configs back off and no shared {@code EntityStorage}
 * bean exists, so this indicator backs off cleanly rather than failing context
 * startup. Per-type health contributors are a future enhancement.
 */
@AutoConfiguration(after = JclaimAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "jclaim.health", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JclaimHealthAutoConfiguration {

    @Bean(name = "jclaimHealthIndicator")
    @ConditionalOnBean(EntityStorage.class)
    @ConditionalOnMissingBean(name = "jclaimHealthIndicator")
    public HealthIndicator jclaimHealthIndicator(EntityStorage storage) {
        return new JclaimHealthIndicator(storage);
    }
}
