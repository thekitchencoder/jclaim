package uk.codery.jclaim.spring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.matching.MatchingPolicy;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.resolver.EntityResolvers;
import uk.codery.jclaim.spring.match.LoggingMatchSink;
import uk.codery.jclaim.spring.match.SpringEventMatchSink;
import uk.codery.jclaim.spring.matching.JclaimMatchingConfiguration;
import uk.codery.jclaim.spring.storage.InMemoryStorageConfiguration;
import uk.codery.jclaim.spring.storage.MongoStorageConfiguration;
import uk.codery.jclaim.spring.storage.PostgresStorageConfiguration;
import uk.codery.jclaim.storage.EntityStorage;

/**
 * Top-level auto-configuration for JClaim. Enables {@link JclaimProperties},
 * imports the three storage configurations (in selection-priority order:
 * Postgres, Mongo, then in-memory fallback), and registers the resolver +
 * default match sink. All beans are {@code @ConditionalOnMissingBean}
 * so applications can override any layer.
 */
@AutoConfiguration
@EnableConfigurationProperties(JclaimProperties.class)
@Import({
        PostgresStorageConfiguration.class,
        MongoStorageConfiguration.class,
        InMemoryStorageConfiguration.class,
        JclaimMatchingConfiguration.class
})
public class JclaimAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MatchEventSink jclaimMatchEventSink(
            JclaimProperties properties,
            ObjectProvider<ApplicationEventPublisher> publishers) {
        return switch (properties.matchSink().type()) {
            case SPRING_EVENTS -> new SpringEventMatchSink(
                    publishers.getIfAvailable(() -> {
                        throw new IllegalStateException(
                                "jclaim.match-sink.type=spring-events requires an ApplicationEventPublisher");
                    }));
            case LOGGING -> new LoggingMatchSink();
            case NOOP -> MatchEventSink.noop();
        };
    }

    // Named "jclaimResolver" (not @Primary) so a v2 multi-resolver setup can wire
    // additional named resolvers without colliding with the starter's default.
    // Suppressed in multi-type mode (any jclaim.entity-types.<type> present) —
    // single- and multi-type modes are mutually exclusive; the per-type
    // resolvers land in Phase 5.
    @Bean("jclaimResolver")
    @ConditionalOnMissingBean
    @Conditional(NoEntityTypesCondition.class)
    public EntityResolver jclaimEntityResolver(
            EntityStorage storage,
            MatchEventSink matchSink,
            MatchingPolicy matchingPolicy,
            JclaimProperties properties) {
        DefaultEntityResolver.Builder builder = DefaultEntityResolver.builder(storage)
                .namespace(properties.urn().namespace())
                .entityType(properties.urn().type())
                .publicIdTemplate(properties.publicId().template())
                .matchEventSink(matchSink)
                .matchingPolicy(matchingPolicy)
                .maxCandidates(properties.matching().maxCandidates());
        if ("off".equalsIgnoreCase(properties.publicId().filter())) {
            builder.allowUnfilteredPublicIds();
        }
        return builder.build();
    }

    /**
     * Multi-type mode: registers one {@link EntityResolver} bean per
     * {@code jclaim.entity-types.<type>} entry. Must be {@code static} so this
     * {@link org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor}
     * is instantiated early (before regular bean post-processing) without forcing
     * premature initialisation of the enclosing configuration.
     */
    @Bean
    @Conditional(EntityTypesConfiguredCondition.class)
    static EntityTypeResolverRegistrar jclaimEntityTypeResolverRegistrar() {
        return new EntityTypeResolverRegistrar();
    }

    /**
     * Multi-type facade: aggregates every {@code jclaimEntityResolver_<type>} bean
     * into an {@link EntityResolvers} registry keyed by the bare type. Resolves the
     * per-type beans by name prefix from the bean factory so the registrar's
     * dynamically-registered definitions are picked up.
     */
    @Bean
    @ConditionalOnMissingBean
    @Conditional(EntityTypesConfiguredCondition.class)
    public EntityResolvers jclaimEntityResolvers(ListableBeanFactory beanFactory) {
        Map<String, EntityResolver> byType = new LinkedHashMap<>();
        // getBean(...) here eagerly instantiates each per-type resolver (and thus its
        // scoped storage: Postgres schema provisioning, Mongo index creation) at
        // context refresh. This is deliberate — it surfaces misconfiguration at
        // startup rather than on first request — but means startup cost scales with
        // the number of entity types. (EntityResolvers itself is not an
        // EntityResolver, so the facade never enumerates itself here.)
        for (String beanName : beanFactory.getBeanNamesForType(EntityResolver.class)) {
            String type = EntityTypeResolverRegistrar.typeOf(beanName);
            if (type != null) {
                byType.put(type, beanFactory.getBean(beanName, EntityResolver.class));
            }
        }
        return EntityResolvers.of(byType);
    }
}
