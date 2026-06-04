package uk.codery.jclaim.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JclaimAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class));

    @Test
    void defaultsToInMemoryResolverWithSpringEventSink() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(EntityResolver.class);
            assertThat(ctx).hasSingleBean(EntityStorage.class);
            assertThat(ctx.getBean(EntityStorage.class)).isInstanceOf(InMemoryEntityStorage.class);
            assertThat(ctx).hasSingleBean(MatchEventSink.class);
        });
    }

    // Pins the IN_MEMORY enum value to the in-memory adapter. Currently
    // equivalent to the default case; will diverge once Mongo/Postgres
    // adapters auto-register and AUTO might pick a different backend.
    @Test
    void explicitInMemoryStorageTypeYieldsInMemoryBean() {
        runner.withPropertyValues("jclaim.storage.type=in-memory").run(ctx -> {
            assertThat(ctx).hasSingleBean(EntityStorage.class);
            assertThat(ctx.getBean(EntityStorage.class)).isInstanceOf(InMemoryEntityStorage.class);
        });
    }

    @Test
    void userProvidedResolverWins() {
        runner.withUserConfiguration(UserResolverConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(EntityResolver.class);
            assertThat(ctx.getBean(EntityResolver.class)).isSameAs(UserResolverConfig.MARKER);
        });
    }

    @Test
    void mongoStorageWiresWhenClientPresentAndTypeAuto() {
        runner
            .withUserConfiguration(MongoClientConfig.class)
            .withPropertyValues("jclaim.storage.mongo.create-indexes=false")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(EntityStorage.class);
                assertThat(ctx.getBean(EntityStorage.class))
                        .isInstanceOf(uk.codery.jclaim.storage.mongo.MongoEntityStorage.class);
            });
    }

    @Test
    void mongoTypeMissingClientFailsStartup() {
        runner
            .withPropertyValues("jclaim.storage.type=mongo")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("MongoClient");
            });
    }

    @Test
    void postgresStorageWiresWhenDataSourcePresent() {
        runner
            .withUserConfiguration(DataSourceConfig.class)
            .withPropertyValues("jclaim.storage.postgres.apply-schema=false")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(EntityStorage.class);
                assertThat(ctx.getBean(EntityStorage.class))
                        .isInstanceOf(uk.codery.jclaim.storage.postgres.PostgresEntityStorage.class);
            });
    }

    @Test
    void postgresTypeMissingDataSourceFailsStartup() {
        runner
            .withPropertyValues("jclaim.storage.type=postgres")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("DataSource");
            });
    }

    @Test
    void mongoStorageWiresUnderExplicitType() {
        runner
            .withUserConfiguration(MongoClientConfig.class)
            .withPropertyValues(
                    "jclaim.storage.type=mongo",
                    "jclaim.storage.mongo.create-indexes=false")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(EntityStorage.class);
                assertThat(ctx.getBean(EntityStorage.class))
                        .isInstanceOf(uk.codery.jclaim.storage.mongo.MongoEntityStorage.class);
            });
    }

    @Test
    void postgresStorageWiresUnderExplicitType() {
        runner
            .withUserConfiguration(DataSourceConfig.class)
            .withPropertyValues(
                    "jclaim.storage.type=postgres",
                    "jclaim.storage.postgres.apply-schema=false")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(EntityStorage.class);
                assertThat(ctx.getBean(EntityStorage.class))
                        .isInstanceOf(uk.codery.jclaim.storage.postgres.PostgresEntityStorage.class);
            });
    }

    @Test
    void loggingMatchSinkSwapsInOnProperty() {
        runner.withPropertyValues("jclaim.match-sink.type=logging").run(ctx -> {
            assertThat(ctx.getBean(uk.codery.jclaim.event.MatchEventSink.class))
                    .isInstanceOf(uk.codery.jclaim.spring.match.LoggingMatchSink.class);
        });
    }

    @Test
    void noopMatchSinkDoesNotPublishOrLog() {
        runner.withPropertyValues("jclaim.match-sink.type=noop").run(ctx -> {
            uk.codery.jclaim.event.MatchEventSink sink =
                    ctx.getBean(uk.codery.jclaim.event.MatchEventSink.class);
            assertThat(sink)
                    .isNotInstanceOf(uk.codery.jclaim.spring.match.SpringEventMatchSink.class)
                    .isNotInstanceOf(uk.codery.jclaim.spring.match.LoggingMatchSink.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserResolverConfig {
        static final EntityResolver MARKER = new StubResolver();
        @Bean EntityResolver userResolver() { return MARKER; }
    }

    @Configuration(proxyBeanMethods = false)
    static class MongoClientConfig {
        @Bean
        com.mongodb.client.MongoClient mongoClient() {
            // Offline test — adapter is configured with create-indexes=false so
            // no I/O happens. The URL just needs to be syntactically valid.
            return com.mongodb.client.MongoClients.create("mongodb://localhost:1");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DataSourceConfig {
        @Bean
        javax.sql.DataSource dataSource() {
            // Lightweight non-connecting DataSource. PostgresEntityStorage with
            // applySchema=false doesn't open a connection at build time.
            org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
            ds.setUrl("jdbc:postgresql://localhost:5432/jclaim_test_unused");
            return ds;
        }
    }

    private static final class StubResolver implements EntityResolver {
        @Override public ResolutionResult resolveOrMint(Claim c) { throw new UnsupportedOperationException(); }
        @Override public Entity getByUrn(EntityId u) { throw new UnsupportedOperationException(); }
        @Override public Optional<Entity> findByHumanId(String s) { throw new UnsupportedOperationException(); }
        @Override public Optional<Entity> findByAlias(SourceSystem s, String i) { throw new UnsupportedOperationException(); }
        @Override public Entity addAlias(EntityId u, SourceSystem s, String i) { throw new UnsupportedOperationException(); }
        @Override public Set<Entity> findCandidates(Claim c) { throw new UnsupportedOperationException(); }
    }
}
