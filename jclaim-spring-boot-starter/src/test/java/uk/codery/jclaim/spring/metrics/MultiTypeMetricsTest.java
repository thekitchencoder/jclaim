package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.resolver.EntityResolvers;
import uk.codery.jclaim.spring.EntityTypeResolverRegistrar;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins Phase-6 per-type metrics: in multi-type mode every per-type resolver is
 * decorated with a {@code type=<entity-type>}-tagged {@link MeteredEntityResolver},
 * and no {@code @Primary} {@link EntityResolver} is introduced.
 */
class MultiTypeMetricsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JclaimAutoConfiguration.class,
                    JclaimMetricsAutoConfiguration.class,
                    JclaimMultiTypeMetricsAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues(
                    "jclaim.storage.type=in-memory",
                    "jclaim.entity-types.customer.human-id.template=????-?",
                    "jclaim.entity-types.vehicle.human-id.template=????-?");

    private static Claim claim(String source, String sourceId) {
        return new Claim(SourceSystem.of(source), sourceId, List.of());
    }

    @Test
    void perTypeResolveCountersCarryTypeTag() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            EntityResolvers resolvers = ctx.getBean(EntityResolvers.class);

            // customer: one mint then one match (same alias) => 2 resolve calls.
            resolvers.forType("customer").resolveOrMint(claim("crm", "c1"));
            resolvers.forType("customer").resolveOrMint(claim("crm", "c1"));
            // vehicle: one mint.
            resolvers.forType("vehicle").resolveOrMint(claim("dvla", "v1"));

            assertThat(registry.find("jclaim.resolve").tags("type", "customer", "outcome", "minted")
                    .counter().count()).isEqualTo(1.0);
            assertThat(registry.find("jclaim.resolve").tags("type", "customer", "outcome", "matched")
                    .counter().count()).isEqualTo(1.0);
            assertThat(registry.find("jclaim.resolve").tags("type", "vehicle", "outcome", "minted")
                    .counter().count()).isEqualTo(1.0);
            // No vehicle "matched" series was created.
            assertThat(registry.find("jclaim.resolve").tags("type", "vehicle", "outcome", "matched")
                    .counter()).isNull();
        });
    }

    @Test
    void facadeHoldsMeteredResolvers() {
        runner.run(ctx -> {
            EntityResolvers resolvers = ctx.getBean(EntityResolvers.class);
            assertThat(resolvers.forType("customer")).isInstanceOf(MeteredEntityResolver.class);
            assertThat(resolvers.forType("vehicle")).isInstanceOf(MeteredEntityResolver.class);
            // The per-type beans themselves are the metered wrappers.
            assertThat(ctx.getBean("jclaimEntityResolver_customer", EntityResolver.class))
                    .isInstanceOf(MeteredEntityResolver.class);
        });
    }

    @Test
    void noPrimaryEntityResolverInMultiTypeMode() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // Both per-type resolver beans are present...
            assertThat(ctx.getBeanProvider(EntityResolver.class).stream()
                    .filter(r -> r instanceof MeteredEntityResolver).count()).isEqualTo(2L);
            // ...and a plain by-type lookup is ambiguous (no @Primary winner).
            assertThatThrownBy(() -> ctx.getBean(EntityResolver.class))
                    .isInstanceOf(org.springframework.beans.factory.NoUniqueBeanDefinitionException.class);
        });
    }

    @Test
    void disabledByPropertyLeavesResolversUnmetered() {
        runner.withPropertyValues("jclaim.metrics.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean("jclaimEntityResolver_customer", EntityResolver.class))
                    .isNotInstanceOf(MeteredEntityResolver.class);
        });
    }

    /**
     * Covers the {@link MultiTypeMetricsBeanPostProcessor} no-{@link MeterRegistry}
     * fallback: metrics are enabled and the BPP is registered, but NO
     * {@code MeterRegistry} bean is present in the context, so
     * {@code registryProvider.getIfAvailable()} returns {@code null} and the
     * per-type resolvers are returned undecorated. Context still starts.
     */
    @Test
    void noMeterRegistryLeavesResolversUnmetered() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JclaimAutoConfiguration.class,
                        JclaimMetricsAutoConfiguration.class,
                        JclaimMultiTypeMetricsAutoConfiguration.class))
                // Deliberately NO MeterRegistry bean.
                .withPropertyValues(
                        "jclaim.storage.type=in-memory",
                        "jclaim.entity-types.customer.human-id.template=????-?",
                        "jclaim.entity-types.vehicle.human-id.template=????-?")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("jclaimMultiTypeMetricsBeanPostProcessor");
                    assertThat(ctx.getBean("jclaimEntityResolver_customer", EntityResolver.class))
                            .isNotInstanceOf(MeteredEntityResolver.class);
                    assertThat(ctx.getBean("jclaimEntityResolver_vehicle", EntityResolver.class))
                            .isNotInstanceOf(MeteredEntityResolver.class);
                });
    }

    @Test
    void typeOfStripsResolverPrefix() {
        assertThat(EntityTypeResolverRegistrar.typeOf("jclaimEntityResolver_customer"))
                .isEqualTo("customer");
        assertThat(EntityTypeResolverRegistrar.typeOf("somethingElse")).isNull();
    }
}
