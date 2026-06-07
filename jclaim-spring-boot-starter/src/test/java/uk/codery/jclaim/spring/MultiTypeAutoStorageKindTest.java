package uk.codery.jclaim.spring;

import javax.sql.DataSource;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.mongo.MongoEntityStorage;
import uk.codery.jclaim.storage.postgres.PostgresEntityStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link EntityTypeResolverRegistrar#resolveStorageKind} AUTO inference and
 * the Mongo {@code buildStorage}/{@code resolveConnection} arms that the
 * in-memory and Docker paths never reach.
 *
 * <p>Under {@code jclaim.storage.type=auto} the registrar infers the storage
 * kind from the available connection beans: a {@link DataSource} present →
 * Postgres; else a {@link MongoClient} present → Mongo; else in-memory. We
 * assert the inferred kind through the concrete type of the per-type
 * {@code jclaimEntityStorage_<type>} bean — a real, observable consequence of
 * the inference, not a line-touch. Schema/index creation is disabled
 * ({@code apply-schema=false} / {@code create-indexes=false}) so the storages
 * build over mock connections without a live backend.
 */
class MultiTypeAutoStorageKindTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class));

    /** A primary {@link DataSource} bean (mock — never connected). */
    @Configuration(proxyBeanMethods = false)
    static class DataSourceConfig {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    /** A primary {@link MongoClient} whose db/collection navigation is stubbed. */
    @Configuration(proxyBeanMethods = false)
    static class MongoClientConfig {
        @Bean
        @SuppressWarnings("unchecked")
        MongoClient mongoClient() {
            MongoClient client = mock(MongoClient.class);
            MongoDatabase db = mock(MongoDatabase.class);
            MongoCollection<Document> coll = mock(MongoCollection.class);
            when(client.getDatabase(org.mockito.ArgumentMatchers.anyString())).thenReturn(db);
            when(db.getCollection(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(Document.class))).thenReturn(coll);
            return client;
        }
    }

    // -- AUTO -> POSTGRES ---------------------------------------------------

    @Test
    void autoInfersPostgresWhenDataSourcePresent() {
        runner.withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(
                        // type UNSET defaults to AUTO; a DataSource bean is present.
                        "jclaim.storage.postgres.apply-schema=false",
                        "jclaim.entity-types.customer.public-id.template=????-?")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    EntityStorage storage =
                            ctx.getBean("jclaimEntityStorage_customer", EntityStorage.class);
                    // AUTO chose Postgres: the scoped storage is the Postgres adapter.
                    assertThat(storage).isInstanceOf(PostgresEntityStorage.class);
                });
    }

    /**
     * A per-type explicit {@code storage.schema} drives the non-null arm of the
     * Postgres schema ternary in {@code buildPostgresStorage} (the default arm —
     * schema-defaults-to-type — is covered by the Docker and AUTO-default paths).
     * The Postgres adapter builds over the mock DataSource ({@code apply-schema=false}
     * so no DDL runs).
     */
    @Test
    void postgresExplicitSchemaPerType() {
        runner.withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(
                        "jclaim.storage.type=postgres",
                        "jclaim.storage.postgres.apply-schema=false",
                        "jclaim.entity-types.customer.storage.schema=cust-schema")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean("jclaimEntityStorage_customer", EntityStorage.class))
                            .isInstanceOf(PostgresEntityStorage.class);
                });
    }

    // -- AUTO -> MONGO ------------------------------------------------------

    @Test
    void autoInfersMongoWhenOnlyMongoClientPresent() {
        runner.withUserConfiguration(MongoClientConfig.class)
                .withPropertyValues(
                        "jclaim.storage.type=auto",
                        // no DataSource bean -> AUTO falls through to MongoClient.
                        "jclaim.storage.mongo.create-indexes=false",
                        "jclaim.entity-types.customer.public-id.template=????-?")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    EntityStorage storage =
                            ctx.getBean("jclaimEntityStorage_customer", EntityStorage.class);
                    // AUTO chose Mongo: the scoped storage is the Mongo adapter, built
                    // over the stubbed client.getDatabase(..).getCollection(..) chain.
                    assertThat(storage).isInstanceOf(MongoEntityStorage.class);
                });
    }

    // -- MONGO buildStorage + reserveScope ternaries (explicit overrides) ---

    /**
     * A per-type {@code storage.mongo-client} + {@code storage.collection-name}
     * override drives the non-null arms of the Mongo {@code reserveScope}
     * ternaries and {@code resolveConnection}'s named-bean lookup (the
     * {@code beanName != null} branch). The named client is resolved and the
     * collection built from the explicit collection name.
     */
    @Test
    void mongoExplicitClientAndCollectionResolveNamedBean() {
        runner.withUserConfiguration(MongoClientConfig.class)
                .withPropertyValues(
                        "jclaim.storage.type=mongo",
                        "jclaim.storage.mongo.create-indexes=false",
                        // named client bean ("mongoClient") + explicit collection name.
                        "jclaim.entity-types.customer.storage.mongo-client=mongoClient",
                        "jclaim.entity-types.customer.storage.collection-name=customers")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    EntityStorage storage =
                            ctx.getBean("jclaimEntityStorage_customer", EntityStorage.class);
                    assertThat(storage).isInstanceOf(MongoEntityStorage.class);
                });
    }

    /**
     * Two Mongo types that OMIT both {@code mongo-client} and {@code collection-name}
     * drive the null (default) arms of the Mongo {@code reserveScope} ternaries:
     * connection defaults to the primary client and the collection defaults to the
     * type key. The two distinct type keys yield distinct scopes, so registration
     * succeeds and each type's storage is the Mongo adapter over the default
     * collection ({@code customer} / {@code vehicle}).
     */
    @Test
    void mongoDefaultClientAndCollectionPerType() {
        runner.withUserConfiguration(MongoClientConfig.class)
                .withPropertyValues(
                        "jclaim.storage.type=mongo",
                        "jclaim.storage.mongo.create-indexes=false",
                        "jclaim.entity-types.customer.public-id.template=????-?",
                        "jclaim.entity-types.vehicle.public-id.template=????-?")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean("jclaimEntityStorage_customer", EntityStorage.class))
                            .isInstanceOf(MongoEntityStorage.class);
                    assertThat(ctx.getBean("jclaimEntityStorage_vehicle", EntityStorage.class))
                            .isInstanceOf(MongoEntityStorage.class);
                });
    }

    // -- AUTO -> IN_MEMORY: DataSource takes priority over MongoClient ------

    /**
     * When BOTH a {@link DataSource} and a {@link MongoClient} are present, AUTO
     * picks Postgres (the DataSource arm short-circuits before the MongoClient
     * arm). Asserting the Postgres adapter is chosen pins that ordering.
     */
    @Test
    void autoPrefersDataSourceOverMongoClient() {
        runner.withUserConfiguration(DataSourceConfig.class, MongoClientConfig.class)
                .withPropertyValues(
                        "jclaim.storage.type=auto",
                        "jclaim.storage.postgres.apply-schema=false",
                        "jclaim.entity-types.customer.public-id.template=????-?")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    EntityStorage storage =
                            ctx.getBean("jclaimEntityStorage_customer", EntityStorage.class);
                    assertThat(storage).isInstanceOf(PostgresEntityStorage.class);
                });
    }
}
