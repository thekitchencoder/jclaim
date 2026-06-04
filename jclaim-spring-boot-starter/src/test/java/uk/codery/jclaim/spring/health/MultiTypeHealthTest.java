package uk.codery.jclaim.spring.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins Phase-6 per-type health: in multi-type mode one
 * {@code jclaimHealthIndicator_<type>} contributor exists per type (each UP over
 * its own scoped storage), and the single-type {@code jclaimHealthIndicator}
 * backs off.
 */
class MultiTypeHealthTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JclaimAutoConfiguration.class,
                    JclaimHealthAutoConfiguration.class))
            .withPropertyValues(
                    "jclaim.storage.type=in-memory",
                    "jclaim.entity-types.customer.human-id.template=????-?",
                    "jclaim.entity-types.vehicle.human-id.template=????-?");

    @Test
    void oneUpIndicatorPerType() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasBean("jclaimHealthIndicator_customer");
            assertThat(ctx).hasBean("jclaimHealthIndicator_vehicle");

            HealthIndicator customer =
                    ctx.getBean("jclaimHealthIndicator_customer", HealthIndicator.class);
            HealthIndicator vehicle =
                    ctx.getBean("jclaimHealthIndicator_vehicle", HealthIndicator.class);

            assertThat(customer.health().getStatus()).isEqualTo(Status.UP);
            assertThat(vehicle.health().getStatus()).isEqualTo(Status.UP);
            assertThat(customer.health().getDetails())
                    .containsEntry("storage", "InMemoryEntityStorage");
        });
    }

    @Test
    void singleTypeIndicatorAbsentInMultiTypeMode() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean("jclaimHealthIndicator");
        });
    }

    @Test
    void disabledByProperty() {
        runner.withPropertyValues("jclaim.health.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean("jclaimHealthIndicator_customer");
            assertThat(ctx).doesNotHaveBean("jclaimHealthIndicator_vehicle");
        });
    }
}
