package uk.codery.jclaim.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.match.LoggingMatchSink;
import uk.codery.jclaim.spring.match.SpringEventMatchSink;
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
        InMemoryStorageConfiguration.class
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

    @Bean
    @ConditionalOnMissingBean
    public EntityResolver jclaimEntityResolver(
            EntityStorage storage,
            MatchEventSink matchSink,
            JclaimProperties properties) {
        return DefaultEntityResolver.builder(storage)
                .namespace(properties.namespace())
                .matchEventSink(matchSink)
                .build();
    }
}
