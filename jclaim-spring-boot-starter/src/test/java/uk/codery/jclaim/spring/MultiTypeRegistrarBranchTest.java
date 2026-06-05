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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the remaining in-memory-reachable predicate branches in
 * {@link EntityTypeResolverRegistrar}: the redundant-but-agreeing per-type
 * {@code urn.type}, an explicit-default per-type {@code urn.namespace}, a blank
 * {@code matching.spec}, and the two spec-path {@code normalise} forms (bare and
 * already-leading-slash). Each asserts the observable resolver outcome rather
 * than merely touching the line.
 */
class MultiTypeRegistrarBranchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class));

    private static Entity mint(EntityResolver r, String source, String sourceId) {
        return ((ResolutionResult.Minted) r.resolveOrMint(
                new Claim(SourceSystem.of(source), sourceId, List.of()))).entity();
    }

    /**
     * A per-type {@code urn.type} set to the SAME value as the map key agrees with
     * the authoritative key, so startup succeeds (exercises the
     * {@code !entryType.equals(type)} false arm of {@code validateTypeKeyAgreement}).
     */
    @Test
    void perTypeUrnTypeEqualToKeyIsAccepted() {
        runner.withPropertyValues(
                        "jclaim.storage.type=in-memory",
                        "jclaim.entity-types.customer.urn.type=customer")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    Entity e = mint(ctx.getBean("jclaimEntityResolver_customer", EntityResolver.class),
                            "crm", "c1");
                    assertThat(e.id().type()).isEqualTo("customer");
                });
    }

    /**
     * A per-type {@code urn.namespace} explicitly set to the {@code Urn} default
     * ({@code codery}) takes the inherit branch (override detection compares against
     * the default), so the minted URN carries the top-level namespace. Here the
     * top-level is {@code acme}, proving the explicit-default per-type value did NOT
     * pin {@code codery} — the documented sentinel heuristic.
     */
    @Test
    void perTypeNamespaceSetToDefaultInheritsTopLevel() {
        runner.withPropertyValues(
                        "jclaim.storage.type=in-memory",
                        "jclaim.urn.namespace=acme",
                        "jclaim.entity-types.customer.urn.namespace=codery")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    Entity e = mint(ctx.getBean("jclaimEntityResolver_customer", EntityResolver.class),
                            "crm", "c1");
                    assertThat(e.id().namespace()).isEqualTo("acme");
                });
    }

    /**
     * A blank {@code matching.spec} falls through to the alias-only default policy
     * (exercises the {@code spec.isBlank()} true arm). The resolver still mints.
     */
    @Test
    void blankSpecFallsBackToAliasOnly() {
        runner.withPropertyValues(
                        "jclaim.storage.type=in-memory",
                        "jclaim.entity-types.customer.matching.spec=   ")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    Entity e = mint(ctx.getBean("jclaimEntityResolver_customer", EntityResolver.class),
                            "crm", "c1");
                    assertThat(e.id().type()).isEqualTo("customer");
                });
    }

    /**
     * A bare (no {@code classpath:} prefix, no leading slash) spec path is
     * normalised by prepending a leading slash. Exercises the
     * {@code startsWith("classpath:")} false arm and the
     * {@code startsWith("/")} false arm of {@code normalise}; the jspec policy
     * builds, proving the resource was found.
     */
    @Test
    void bareSpecPathNormalisesAndBuildsJspecPolicy() {
        runner.withPropertyValues(
                        "jclaim.storage.type=in-memory",
                        "jclaim.entity-types.customer.matching.spec=matching/email.yaml")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    Entity e = mint(ctx.getBean("jclaimEntityResolver_customer", EntityResolver.class),
                            "crm", "c1");
                    assertThat(e.id().type()).isEqualTo("customer");
                });
    }

    /**
     * A {@code classpath:/...} spec path (already leading-slash after the prefix is
     * stripped) is used verbatim. Exercises the {@code startsWith("/")} true arm of
     * {@code normalise}; the jspec policy builds.
     */
    @Test
    void classpathLeadingSlashSpecPathUsedVerbatim() {
        runner.withPropertyValues(
                        "jclaim.storage.type=in-memory",
                        "jclaim.entity-types.customer.matching.spec=classpath:/matching/email.yaml")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    Entity e = mint(ctx.getBean("jclaimEntityResolver_customer", EntityResolver.class),
                            "crm", "c1");
                    assertThat(e.id().type()).isEqualTo("customer");
                });
    }
}
