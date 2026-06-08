package uk.codery.jclaim.spring;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import uk.codery.jclaim.matching.MatchingPolicy;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.resolver.EntityResolvers;
import uk.codery.jclaim.spring.health.JclaimHealthAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Phase-5 multi-type wiring: one {@link EntityResolver} bean per
 * {@code jclaim.entity-types.<type>} entry (prefixed bean name + type qualifier),
 * the {@link EntityResolvers} facade, and the fail-fast failure modes.
 */
class MultiTypeWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class));

    /** Two in-memory types, customer + vehicle, namespace overridden to acme. */
    private ApplicationContextRunner twoTypes() {
        return runner.withPropertyValues(
                "jclaim.storage.type=in-memory",
                "jclaim.urn.namespace=acme",
                "jclaim.entity-types.customer.public-id.template=????-?",
                "jclaim.entity-types.vehicle.public-id.template=????-?");
    }

    private static Entity mint(EntityResolver r, String source, String sourceId) {
        return ((ResolutionResult.Minted) r.resolveOrMint(
                new Claim(SourceSystem.of(source), sourceId, List.of()))).entity();
    }

    // -- Task 5.1 -----------------------------------------------------------

    @Test
    void registersQualifiedResolverPerType() {
        twoTypes().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasBean("jclaimEntityResolver_customer");
            assertThat(ctx).hasBean("jclaimEntityResolver_vehicle");

            Entity e = mint(ctx.getBean("jclaimEntityResolver_customer", EntityResolver.class),
                    "crm", "c1");
            assertThat(e.id().type()).isEqualTo("customer");
            assertThat(e.id().namespace()).isEqualTo("acme");
        });
    }

    @Test
    void qualifierInjectionResolvesByTypeKey() {
        twoTypes().withUserConfiguration(QualifiedProbe.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            QualifiedProbe probe = ctx.getBean(QualifiedProbe.class);
            assertThat(probe.customer).isNotNull();
            Entity e = mint(probe.customer, "crm", "c-probe");
            assertThat(e.id().type()).isEqualTo("customer");
        });
    }

    // -- Task 5.2 -----------------------------------------------------------

    @Test
    void facadeAggregatesAllTypes() {
        twoTypes().run(ctx -> {
            EntityResolvers resolvers = ctx.getBean(EntityResolvers.class);
            assertThat(resolvers.types()).containsExactlyInAnyOrder("customer", "vehicle");

            Entity e = mint(resolvers.forType("customer"), "crm", "c1");
            assertThat(e.id().type()).isEqualTo("customer");
        });
    }

    // -- Task 5.3: failure modes -------------------------------------------

    @Test
    void scopeCollisionFailsStartup() {
        runner.withPropertyValues(
                "jclaim.storage.type=in-memory",
                "jclaim.entity-types.customer.storage.schema=shared",
                "jclaim.entity-types.vehicle.storage.schema=shared")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasStackTraceContaining("same storage scope");
                });
    }

    @Test
    void missingPerTypeConnectionFailsStartup() {
        runner.withPropertyValues(
                "jclaim.storage.type=postgres",
                "jclaim.entity-types.customer.storage.datasource=ghost")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasStackTraceContaining("Entity type 'customer'")
                            .hasStackTraceContaining("ghost");
                });
    }

    // jspec IS on the starter's test classpath, so we assert the positive path:
    // a spec-configured type builds a non-aliasOnly (jspec-backed) policy rather
    // than falling through to alias-only. Simulating jspec-absent would require a
    // filtered classloader; the fail-fast branch is exercised by the equivalent
    // single-type path in JclaimMatchingConfigurationTest.
    @Test
    void specBuildsJspecPolicyWhenModulePresent() {
        runner.withPropertyValues(
                "jclaim.storage.type=in-memory",
                "jclaim.entity-types.customer.matching.spec=classpath:matching/email.yaml")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("jclaimEntityResolver_customer");
                    // The resolver mints/matches via the jspec policy without error.
                    Entity e = mint(ctx.getBean("jclaimEntityResolver_customer", EntityResolver.class),
                            "crm", "c1");
                    assertThat(e.id().type()).isEqualTo("customer");
                    assertThat(MatchingPolicy.aliasOnly()).isNotNull();
                });
    }

    /**
     * The facade aggregates only the {@code jclaimEntityResolver_<type>} beans. A
     * stray application-defined {@link EntityResolver} bean (whose name does not
     * match the per-type prefix, so {@code typeOf} returns {@code null}) is
     * excluded — exercising the {@code type != null} false arm of the aggregation
     * loop. The facade therefore holds exactly the two per-type resolvers.
     */
    @Test
    void facadeExcludesNonPerTypeResolverBeans() {
        twoTypes().withUserConfiguration(StrayResolverConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // The stray resolver bean is present in the context...
            assertThat(ctx).hasBean("strayResolver");
            // ...but the facade only keys the two per-type resolvers.
            EntityResolvers resolvers = ctx.getBean(EntityResolvers.class);
            assertThat(resolvers.types()).containsExactlyInAnyOrder("customer", "vehicle");
            // The stray instance is not reachable via any facade type key.
            EntityResolver stray = ctx.getBean("strayResolver", EntityResolver.class);
            assertThat(resolvers.find("customer")).get().isNotSameAs(stray);
            assertThat(resolvers.find("vehicle")).get().isNotSameAs(stray);
        });
    }

    // -- C1: health auto-config must back off in multi-type mode ------------

    /**
     * In multi-type mode no single {@link uk.codery.jclaim.storage.EntityStorage}
     * bean exists (the single-type storage configs back off via
     * {@code NoEntityTypesCondition}). The Actuator health auto-config — which is
     * on the compile classpath — must therefore back off too, rather than try to
     * autowire an absent {@code EntityStorage} and break context startup.
     */
    @Test
    void healthAutoConfigBacksOffInMultiTypeMode() {
        twoTypes()
                .withConfiguration(AutoConfigurations.of(JclaimHealthAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("jclaimEntityResolver_customer");
                    assertThat(ctx).doesNotHaveBean("jclaimHealthIndicator");
                });
    }

    // -- I2: config inheritance --------------------------------------------

    /**
     * A per-type entry that OMITS {@code urn.namespace} inherits the top-level
     * {@code jclaim.urn.namespace}. Here the top-level namespace is {@code acme}
     * and neither type sets a per-type namespace, so a minted URN carries
     * {@code acme}.
     */
    @Test
    void perTypeNamespaceInheritsTopLevelDefault() {
        twoTypes().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            Entity e = mint(ctx.getBean("jclaimEntityResolver_vehicle", EntityResolver.class),
                    "dvla", "v1");
            assertThat(e.id().namespace()).isEqualTo("acme");
        });
    }

    @Test
    void urnTypeDisagreeingWithKeyFailsStartup() {
        runner.withPropertyValues(
                "jclaim.storage.type=in-memory",
                "jclaim.entity-types.customer.urn.type=person")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasStackTraceContaining("conflicts with the map key");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class StrayResolverConfig {
        @org.springframework.context.annotation.Bean
        EntityResolver strayResolver() {
            return uk.codery.jclaim.resolver.DefaultEntityResolver
                    .builder(new uk.codery.jclaim.storage.memory.InMemoryEntityStorage())
                    .namespace("acme").entityType("stray").build();
        }
    }

    @Test
    void malformedTypeKeyFailsStartupAtRegistration() {
        runner.withPropertyValues(
                "jclaim.storage.type=in-memory",
                "jclaim.entity-types.bad_key.public-id.template=????-?")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasStackTraceContaining("must match");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class QualifiedProbe {
        // Constructor injection: the @Qualifier on the constructor parameter is what
        // selects the bean; no field-level @Qualifier needed.
        final EntityResolver customer;

        QualifiedProbe(@Qualifier("customer") EntityResolver customer) {
            this.customer = customer;
        }
    }
}
