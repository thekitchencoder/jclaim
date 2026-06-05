package uk.codery.jclaim.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link EntityTypeResolverRegistrar}'s scope-reservation arms for the
 * Postgres and Mongo storage kinds. Scope reservation runs at bean-definition
 * registration (before any storage backend is touched), so a same-scope clash
 * fails the context fast for each kind without needing a real DataSource /
 * MongoClient. This covers the POSTGRES and MONGO branches of
 * {@code reserveScope} that the in-memory and Docker paths do not reach.
 */
class MultiTypeScopeRegistrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class));

    @Test
    void postgresSameSchemaOnSameDatasourceClashesAtRegistration() {
        runner.withPropertyValues(
                        "jclaim.storage.type=postgres",
                        // same schema, same (primary) datasource -> POSTGRES-arm scope clash.
                        "jclaim.entity-types.customer.storage.schema=shared",
                        "jclaim.entity-types.vehicle.storage.schema=shared")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasStackTraceContaining("same storage scope")
                            .hasStackTraceContaining("POSTGRES");
                });
    }

    @Test
    void mongoSameCollectionOnSameClientClashesAtRegistration() {
        runner.withPropertyValues(
                        "jclaim.storage.type=mongo",
                        // same collection, same (primary) mongo client -> MONGO-arm clash.
                        "jclaim.entity-types.customer.storage.collection-name=shared",
                        "jclaim.entity-types.vehicle.storage.collection-name=shared")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasStackTraceContaining("same storage scope")
                            .hasStackTraceContaining("MONGO");
                });
    }

    @Test
    void postgresDistinctSchemasOnSameDatasourceDoNotClash() {
        // Distinct schemas on the (same) primary datasource -> reserveScope reserves
        // two distinct POSTGRES keys and registration proceeds. The clash check
        // passing is the branch under test; the missing primary DataSource then
        // fails LATER at storage instantiation (a different, already-covered path),
        // so we assert the failure is NOT the scope clash.
        runner.withPropertyValues(
                        "jclaim.storage.type=postgres",
                        "jclaim.entity-types.customer.storage.schema=cust",
                        "jclaim.entity-types.vehicle.storage.schema=veh")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasStackTraceContaining("requires a DataSource");
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .hasMessageNotContaining("same storage scope");
                });
    }
}
