package uk.codery.jclaim.spring;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.resolver.EntityResolvers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@link EntityTypeResolverRegistrar} config-inheritance branches
 * on in-memory storage (no Docker): per-type {@code urn.namespace} override vs
 * inheritance, per-type {@code matching.max-candidates} override, AUTO
 * storage-kind resolution to in-memory, and the in-memory distinct explicit
 * scope path that does NOT collide.
 */
class MultiTypeInheritanceTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class));

    private static Entity mint(EntityResolver r, String source, String sourceId) {
        ResolutionResult result = r.resolveOrMint(
                new Claim(SourceSystem.of(source), sourceId, List.of()));
        return ((ResolutionResult.Minted) result).entity();
    }

    @Test
    void perTypeNamespaceOverrideWinsWhileSiblingInherits() {
        runner.withPropertyValues(
                        "jclaim.storage.type=in-memory",
                        "jclaim.urn.namespace=acme",
                        // customer pins its own namespace (differs from default + top-level)
                        "jclaim.entity-types.customer.urn.namespace=customers-ns",
                        // vehicle omits it -> inherits the top-level 'acme'
                        "jclaim.entity-types.vehicle.public-id.template=????-?")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    EntityResolvers resolvers = ctx.getBean(EntityResolvers.class);

                    Entity c = mint(resolvers.forType("customer"), "crm", "c1");
                    Entity v = mint(resolvers.forType("vehicle"), "dvla", "v1");

                    assertThat(c.id().namespace()).isEqualTo("customers-ns");
                    assertThat(v.id().namespace()).isEqualTo("acme");
                });
    }

    @Test
    void perTypeMaxCandidatesOverrideTakesEffect() {
        // A per-type max-candidates differing from the default (100) selects the
        // override branch in resolveMaxCandidates; the resolver still mints/matches.
        // The two halves of the thread-through are each covered elsewhere:
        // resolveMaxCandidates returning the configured value is unit-tested in
        // MultiTypeRegistrarBranchTest, and the builder's maxCandidates actually
        // capping the candidate pool is proven behaviourally in core's
        // PolicyDrivenResolveOrMintTest#candidatePoolTruncated_flagsTruncationInEvent.
        runner.withPropertyValues(
                        "jclaim.storage.type=in-memory",
                        "jclaim.entity-types.customer.matching.max-candidates=7")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    EntityResolver r = ctx.getBean("jclaimEntityResolver_customer", EntityResolver.class);
                    Entity e = mint(r, "crm", "c1");
                    assertThat(e.id().type()).isEqualTo("customer");
                });
    }

    @Test
    void autoStorageKindResolvesToInMemoryWithNoConnectionBeans() {
        // No DataSource / MongoClient on the classpath of this context => AUTO falls
        // through to in-memory. Two types with no explicit scope never collide.
        runner.withPropertyValues(
                        "jclaim.storage.type=auto",
                        "jclaim.entity-types.customer.public-id.template=????-?",
                        "jclaim.entity-types.vehicle.public-id.template=????-?")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    EntityResolvers resolvers = ctx.getBean(EntityResolvers.class);
                    assertThat(resolvers.types()).containsExactlyInAnyOrder("customer", "vehicle");
                    assertThat(mint(resolvers.forType("customer"), "crm", "c1").id().type())
                            .isEqualTo("customer");
                });
    }

    @Test
    void distinctExplicitInMemoryScopesDoNotCollide() {
        // Both types name an explicit (distinct) schema -> the in-memory over-strict
        // guard reserves two distinct keys and does NOT fail.
        runner.withPropertyValues(
                        "jclaim.storage.type=in-memory",
                        "jclaim.entity-types.customer.storage.schema=cust",
                        "jclaim.entity-types.vehicle.storage.schema=veh")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("jclaimEntityResolver_customer");
                    assertThat(ctx).hasBean("jclaimEntityResolver_vehicle");
                });
    }
}
