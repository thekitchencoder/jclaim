package uk.codery.jclaim.spring.health;

import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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
 */
@AutoConfiguration(after = JclaimAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "jclaim.health", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JclaimHealthAutoConfiguration {

    @Bean(name = "jclaimHealthIndicator")
    @ConditionalOnMissingBean(name = "jclaimHealthIndicator")
    public HealthIndicator jclaimHealthIndicator(EntityStorage storage) {
        return new JclaimHealthIndicator(storage);
    }
}
