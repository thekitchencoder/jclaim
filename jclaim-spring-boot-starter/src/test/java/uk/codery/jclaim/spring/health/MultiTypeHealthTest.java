package uk.codery.jclaim.spring.health;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolvers;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;
import uk.codery.jclaim.storage.EntityStorage;

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

    /**
     * Pins the Phase-6 shared-storage-instance guarantee: the per-type resolver
     * and the per-type health indicator observe ONE {@code EntityStorage}
     * instance (so schema/index creation runs once per type). Proven by minting
     * an entity through {@code resolvers.forType("customer")} and then asserting
     * the {@code jclaimEntityStorage_customer} bean — the same instance the health
     * indicator probes — observes that entity. If the resolver built its own
     * storage rather than sharing the registered bean, the bean would not see the
     * minted entity and this would fail.
     */
    @Test
    void resolverAndHealthShareOneStoragePerType() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();

            EntityResolvers resolvers = ctx.getBean(EntityResolvers.class);
            ResolutionResult result = resolvers.forType("customer")
                    .resolveOrMint(new Claim(SourceSystem.of("crm"), "c-shared", List.of()));
            Entity minted = ((ResolutionResult.Minted) result).entity();

            // The storage bean behind the health indicator for this type.
            EntityStorage customerStorage =
                    ctx.getBean("jclaimEntityStorage_customer", EntityStorage.class);

            // Same instance: the storage the resolver wrote through is the storage
            // the health bean probes, so the alias/URN are visible here.
            assertThat(customerStorage.findByAlias(Alias.of("crm", "c-shared")))
                    .contains(minted);
            assertThat(customerStorage.findByUrn(minted.id())).contains(minted);

            // And a different type's storage is a distinct instance that does NOT
            // observe the customer entity.
            EntityStorage vehicleStorage =
                    ctx.getBean("jclaimEntityStorage_vehicle", EntityStorage.class);
            assertThat(vehicleStorage).isNotSameAs(customerStorage);
            assertThat(vehicleStorage.findByAlias(Alias.of("crm", "c-shared"))).isEmpty();
        });
    }

    @Test
    void disabledByProperty() {
        runner.withPropertyValues("jclaim.health.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean("jclaimHealthIndicator_customer");
            assertThat(ctx).doesNotHaveBean("jclaimHealthIndicator_vehicle");
        });
    }

    /**
     * Pins the {@code PerTypeHealthIndicatorRegistrar} idempotency skip: when an
     * application already defines a {@code jclaimHealthIndicator_<type>} bean, the
     * registrar's {@code containsBeanDefinition} check short-circuits and does NOT
     * overwrite it. Proven by a user-supplied DOWN indicator for {@code customer}
     * surviving (the auto-registered one would report UP over its storage), while
     * the un-shadowed {@code vehicle} type still gets the auto UP indicator.
     */
    @Test
    void existingPerTypeIndicatorIsNotOverwritten() {
        runner.withUserConfiguration(PreRegisteredCustomerIndicator.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();

            HealthIndicator customer =
                    ctx.getBean("jclaimHealthIndicator_customer", HealthIndicator.class);
            // The user's DOWN indicator survived — the registrar skipped this name.
            assertThat(customer.health().getStatus()).isEqualTo(Status.DOWN);

            // The vehicle type was not shadowed, so it gets the auto UP indicator.
            HealthIndicator vehicle =
                    ctx.getBean("jclaimHealthIndicator_vehicle", HealthIndicator.class);
            assertThat(vehicle.health().getStatus()).isEqualTo(Status.UP);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class PreRegisteredCustomerIndicator {
        @Bean("jclaimHealthIndicator_customer")
        HealthIndicator jclaimHealthIndicatorCustomer() {
            return () -> Health.down().withDetail("source", "user-defined").build();
        }
    }
}
