package uk.codery.jclaim.spring.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class JclaimHealthIndicatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JclaimAutoConfiguration.class,
                    JclaimHealthAutoConfiguration.class));

    @Test
    void registersIndicatorAndReportsUpForInMemoryStorage() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("jclaimHealthIndicator");
            HealthIndicator indicator = ctx.getBean("jclaimHealthIndicator", HealthIndicator.class);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            assertThat(indicator.health().getDetails())
                    .containsEntry("storage", "InMemoryEntityStorage");
        });
    }

    @Test
    void disabledByProperty() {
        runner.withPropertyValues("jclaim.health.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean("jclaimHealthIndicator");
        });
    }
}
