package uk.codery.jclaim.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the single-type / multi-type mode exclusivity: the top-level
 * {@code jclaimResolver} bean is present only when no
 * {@code jclaim.entity-types.<type>} entries are configured. Phase 5 will add
 * the per-type resolver registration; for now multi-type mode simply leaves no
 * resolver bean (the context must still start).
 */
class EntityTypesModeTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class));

    @Test
    void singleTypeResolverPresentWhenNoEntityTypes() {
        runner.run(ctx -> assertThat(ctx).hasBean("jclaimResolver"));
    }

    @Test
    void singleTypeResolverAbsentWhenEntityTypesPresent() {
        runner.withPropertyValues("jclaim.entity-types.customer.human-id.template=????-?")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean("jclaimResolver");
                });
    }
}
