package uk.codery.jclaim.spring.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.codery.jclaim.matching.MatchingPolicy;
import uk.codery.jclaim.spring.JclaimProperties;

import java.util.List;

/**
 * Registers the {@link MatchingPolicy} bean the resolver uses.
 *
 * <p>Three mutually-exclusive wiring paths, all {@code @ConditionalOnMissingBean}
 * so a user-declared {@code MatchingPolicy} always wins:
 *
 * <ul>
 *   <li><b>{@link SpecBacked}</b> — {@code jclaim.matching.spec} is set and the
 *       optional {@code jclaim-matching-jspec} module is on the classpath: builds a
 *       {@code JspecMatchingPolicy} from the named classpath resource. The reference
 *       to {@code JspecMatchingPolicy} lives <em>only</em> inside this
 *       {@code @ConditionalOnClass}-guarded class, so the starter still loads when
 *       the optional module is absent and no spec is configured.</li>
 *   <li><b>{@link SpecBackedButModuleMissing}</b> — {@code jclaim.matching.spec} is
 *       set but {@code jclaim-matching-jspec} is <em>not</em> on the classpath: fails
 *       startup with an actionable message naming the missing module.</li>
 *   <li><b>alias-only default</b> — no spec configured: the outer
 *       {@link #jclaimAliasOnlyMatchingPolicy(JclaimProperties)} bean returns
 *       {@link MatchingPolicy#aliasOnly()} (always available from core).</li>
 * </ul>
 *
 * <p>Validation is eager: {@code JspecMatchingPolicy.fromResource} throws at
 * bean-creation time when the spec path is missing/unparseable, so a bad spec makes
 * the Spring context fail to start rather than deferring to first traffic.
 */
@Configuration(proxyBeanMethods = false)
public class JclaimMatchingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JclaimMatchingConfiguration.class);

    /**
     * Spec-backed policy. {@code JspecMatchingPolicy} is referenced only here, behind
     * {@code @ConditionalOnClass(name=...)}, so absence of the optional module never
     * breaks class-loading of the always-active configuration.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "uk.codery.jclaim.matching.jspec.JspecMatchingPolicy")
    @ConditionalOnProperty(prefix = "jclaim.matching", name = "spec")
    static class SpecBacked {

        @Bean
        @ConditionalOnMissingBean(MatchingPolicy.class)
        MatchingPolicy jclaimMatchingPolicy(JclaimProperties properties) {
            String spec = normalise(properties.matching().spec());
            // Eager validation: a missing/unparseable resource throws here, so the
            // Spring context fails to start rather than deferring to first traffic.
            return uk.codery.jclaim.matching.jspec.JspecMatchingPolicy.fromResource(
                    spec, properties.matching().blockingKeys());
        }

        /**
         * Accepts both Spring-style {@code classpath:matching/x.yaml} and bare
         * {@code matching/x.yaml}. {@code JspecMatchingPolicy.fromResource} resolves
         * via {@code Class.getResourceAsStream}, which needs an absolute
         * (leading-slash) classpath path.
         */
        private static String normalise(String spec) {
            String path = spec.startsWith("classpath:") ? spec.substring("classpath:".length()) : spec;
            return path.startsWith("/") ? path : "/" + path;
        }
    }

    /**
     * Fail-fast path: a spec is configured but the optional {@code jclaim-matching-jspec}
     * module is absent (so {@link SpecBacked}'s {@code @ConditionalOnClass} did not
     * match). Produces a clear startup failure naming the missing module and property.
     * {@code @ConditionalOnMissingClass} makes this mutually exclusive with
     * {@link SpecBacked}, so the two never both contribute a bean.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("uk.codery.jclaim.matching.jspec.JspecMatchingPolicy")
    @ConditionalOnProperty(prefix = "jclaim.matching", name = "spec")
    static class SpecBackedButModuleMissing {

        @Bean
        @ConditionalOnMissingBean(MatchingPolicy.class)
        MatchingPolicy jclaimMatchingPolicyModuleMissing() {
            throw new IllegalStateException(
                    "jclaim.matching.spec is set but the jclaim-matching-jspec module is not on "
                            + "the classpath. Add the uk.codery:jclaim-matching-jspec dependency to "
                            + "enable jspec-backed matching, or unset jclaim.matching.spec to use the "
                            + "alias-only default policy.");
        }
    }

    /**
     * Default path: no spec configured (and no user bean) → alias-only policy.
     * {@code aliasOnly()} lives in core, so this needs no class condition. When a
     * spec <em>is</em> configured, one of the nested configs above supplies the bean
     * first and this {@code @ConditionalOnMissingBean} backs off.
     */
    @Bean
    @ConditionalOnMissingBean(MatchingPolicy.class)
    MatchingPolicy jclaimAliasOnlyMatchingPolicy(JclaimProperties properties) {
        List<String> keys = properties.matching().blockingKeys();
        if (keys != null && !keys.isEmpty()) {
            log.warn("jclaim.matching.blocking-keys={} is set but no jclaim.matching.spec is "
                    + "configured. Blocking keys only apply to a jspec-backed policy, so they are "
                    + "ignored and matching falls back to the alias-only default.", keys);
        }
        return MatchingPolicy.aliasOnly();
    }
}
