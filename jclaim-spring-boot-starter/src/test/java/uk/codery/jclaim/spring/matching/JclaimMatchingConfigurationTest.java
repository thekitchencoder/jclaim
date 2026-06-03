package uk.codery.jclaim.spring.matching;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.codery.jclaim.matching.MatchingPolicy;
import uk.codery.jclaim.matching.TriState;
import uk.codery.jclaim.matching.jspec.JspecMatchingPolicy;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApplicationContextRunner coverage for the {@code jclaim.matching.*} policy
 * auto-configuration (plan acceptance #11-14).
 */
class JclaimMatchingConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class));

    @Test
    void defaultsToAliasOnlyPolicy() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(MatchingPolicy.class);
            MatchingPolicy policy = ctx.getBean(MatchingPolicy.class);
            // aliasOnly() is a stateless shared singleton in core.
            assertThat(policy).isSameAs(MatchingPolicy.aliasOnly());
        });
    }

    @Test
    void specBuildsJspecPolicy() {
        runner.withPropertyValues("jclaim.matching.spec=classpath:matching/email.yaml").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(MatchingPolicy.class);
            assertThat(ctx.getBean(MatchingPolicy.class)).isInstanceOf(JspecMatchingPolicy.class);
        });
    }

    @Test
    void specPathWithoutClasspathPrefixAlsoResolves() {
        runner.withPropertyValues("jclaim.matching.spec=matching/email.yaml").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(MatchingPolicy.class)).isInstanceOf(JspecMatchingPolicy.class);
        });
    }

    @Test
    void userDeclaredPolicyWins() {
        runner
                .withPropertyValues("jclaim.matching.spec=classpath:matching/email.yaml")
                .withUserConfiguration(UserPolicyConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MatchingPolicy.class);
                    assertThat(ctx.getBean(MatchingPolicy.class)).isSameAs(UserPolicyConfig.MARKER);
                });
    }

    @Test
    void missingSpecResourceFailsStartup() {
        runner.withPropertyValues("jclaim.matching.spec=classpath:matching/does-not-exist.yaml").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("does-not-exist.yaml");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserPolicyConfig {
        static final MatchingPolicy MARKER = (Claim claim, Entity candidate) -> TriState.NOT_MATCHED;

        @Bean
        MatchingPolicy userMatchingPolicy() {
            return MARKER;
        }
    }
}
