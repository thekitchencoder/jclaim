package uk.codery.jclaim.spring;

import java.util.Arrays;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.resolver.EntityResolvers;
import uk.codery.jclaim.spring.health.JclaimHealthAutoConfiguration;
import uk.codery.jclaim.spring.metrics.JclaimMetricsAutoConfiguration;
import uk.codery.jclaim.spring.metrics.MeteredEntityResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression lock: with NO {@code jclaim.entity-types} configured (single-type
 * mode) the context must remain behaviourally identical to the pre-multi-type
 * baseline. This test would FAIL if any multi-type machinery — the
 * {@link EntityResolvers} facade or the per-type
 * {@code jclaimEntityResolver_*} / {@code jclaimEntityStorage_*} /
 * {@code jclaimHealthIndicator_*} beans — leaked into single-type mode.
 *
 * <p>It is a real lock, not vacuous: it asserts the ABSENCE of all per-type
 * machinery alongside the PRESENCE of the single-type beans, and exercises the
 * metered + health + URN behaviour end-to-end on in-memory storage.
 */
class SingleTypeRegressionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JclaimAutoConfiguration.class,
                    JclaimMetricsAutoConfiguration.class,
                    JclaimHealthAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    /** No entity-types configured anywhere -> single-type mode. */
    private static final String[] PER_TYPE_PREFIXES = {
            "jclaimEntityResolver_", "jclaimEntityStorage_", "jclaimHealthIndicator_"
    };

    @Test
    void singleTypeBeanPresentAndNoMultiTypeMachineryLeaks() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();

            // The single-type resolver bean is present.
            assertThat(ctx).hasBean("jclaimResolver");

            // No facade in single-type mode.
            assertThat(ctx).doesNotHaveBean("jclaimEntityResolvers");
            assertThat(ctx).doesNotHaveBean(EntityResolvers.class);

            // Enumerate ALL bean names and assert none carry a per-type prefix.
            List<String> perTypeBeans = Arrays.stream(ctx.getBeanDefinitionNames())
                    .filter(name -> Arrays.stream(PER_TYPE_PREFIXES).anyMatch(name::startsWith))
                    .toList();
            assertThat(perTypeBeans)
                    .as("no per-type beans must exist in single-type mode")
                    .isEmpty();
        });
    }

    @Test
    void primaryMeteredResolverResolvesUnambiguously() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // A MeterRegistry user bean + metrics enabled (default) -> the @Primary
            // metered decorator wins and EntityResolver.class resolves cleanly.
            EntityResolver resolver = ctx.getBean(EntityResolver.class);
            assertThat(resolver).isInstanceOf(MeteredEntityResolver.class);
        });
    }

    @Test
    void singleHealthIndicatorReportsUp() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasBean("jclaimHealthIndicator");

            // Only one health indicator (no per-type variants).
            List<String> healthBeans = Arrays.stream(ctx.getBeanDefinitionNames())
                    .filter(name -> name.startsWith("jclaimHealthIndicator"))
                    .toList();
            assertThat(healthBeans).containsExactly("jclaimHealthIndicator");

            HealthIndicator indicator = ctx.getBean("jclaimHealthIndicator", HealthIndicator.class);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        });
    }

    @Test
    void mintedEntityUsesConfiguredUrnTypeAndNamespace() {
        runner.withPropertyValues(
                        "jclaim.urn.type=widget",
                        "jclaim.urn.namespace=acme")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    EntityResolver resolver = ctx.getBean(EntityResolver.class);

                    ResolutionResult result = resolver.resolveOrMint(
                            new Claim(SourceSystem.of("crm"), "w-1", List.of()));
                    assertThat(result).isInstanceOf(ResolutionResult.Minted.class);

                    Entity minted = ((ResolutionResult.Minted) result).entity();
                    assertThat(minted.id().type()).isEqualTo("widget");
                    assertThat(minted.id().namespace()).isEqualTo("acme");
                });
    }
}
