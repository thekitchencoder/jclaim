package uk.codery.jclaim.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import uk.codery.jclaim.event.ConflictEventSink;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.conflict.LoggingConflictSink;
import uk.codery.jclaim.spring.conflict.SpringEventConflictSink;
import uk.codery.jclaim.spring.storage.InMemoryStorageConfiguration;
import uk.codery.jclaim.spring.storage.MongoStorageConfiguration;
import uk.codery.jclaim.spring.storage.PostgresStorageConfiguration;
import uk.codery.jclaim.storage.EntityStorage;

/**
 * Top-level auto-configuration for JClaim. Enables {@link JclaimProperties},
 * imports the three storage configurations (in selection-priority order:
 * Postgres, Mongo, then in-memory fallback), and registers the resolver +
 * default conflict sink. All beans are {@code @ConditionalOnMissingBean}
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
    public ConflictEventSink jclaimConflictEventSink(
            JclaimProperties properties,
            ObjectProvider<ApplicationEventPublisher> publishers) {
        return switch (properties.conflictSink().type()) {
            case SPRING_EVENT -> new SpringEventConflictSink(
                    publishers.getIfAvailable(() -> {
                        throw new IllegalStateException(
                                "jclaim.conflict-sink.type=spring-event requires an ApplicationEventPublisher");
                    }));
            case LOG -> new LoggingConflictSink();
            case NONE -> ConflictEventSink.noop();
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public EntityResolver jclaimEntityResolver(
            EntityStorage storage,
            ConflictEventSink conflictSink,
            JclaimProperties properties) {
        return DefaultEntityResolver.builder(storage)
                .namespace(properties.namespace())
                .conflictSink(conflictSink)
                .build();
    }
}
