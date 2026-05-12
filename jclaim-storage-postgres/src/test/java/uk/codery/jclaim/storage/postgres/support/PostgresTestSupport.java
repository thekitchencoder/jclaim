package uk.codery.jclaim.storage.postgres.support;

import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import uk.codery.jclaim.storage.postgres.PostgresEntityStorage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared Postgres infrastructure for the adapter test suite. Defaults to a
 * Testcontainers-managed {@code postgres:16-alpine} instance; when the
 * environment variable {@code JCLAIM_TEST_POSTGRES_JDBC_URL} is set, that
 * pre-existing database is used instead. Useful for local development on
 * machines where Testcontainers cannot negotiate with the local Docker
 * daemon, and for CI environments that prefer service containers over
 * sibling-container patterns.
 *
 * <p>Either way each call to {@link #freshStorage()} creates a brand-new
 * schema so individual tests see an empty database and the "two isolated
 * storages within one test" corpus case round-trips correctly.
 */
public final class PostgresTestSupport {

    private static final String ENV_JDBC_URL = "JCLAIM_TEST_POSTGRES_JDBC_URL";
    private static final String ENV_USER = "JCLAIM_TEST_POSTGRES_USER";
    private static final String ENV_PASSWORD = "JCLAIM_TEST_POSTGRES_PASSWORD";

    private static volatile boolean initialised;
    private static String jdbcUrl;
    private static String username;
    private static String password;
    private static PostgreSQLContainer<?> container;

    private static final String SCHEMA_RUN_ID = Long.toHexString(System.nanoTime());
    private static final AtomicInteger SCHEMA_COUNTER = new AtomicInteger();

    private PostgresTestSupport() {
    }

    /**
     * Returns a {@link PostgresEntityStorage} bound to a fresh empty schema.
     */
    public static PostgresEntityStorage freshStorage() {
        ensureInitialised();
        String schema = "s_" + SCHEMA_RUN_ID + "_" + SCHEMA_COUNTER.incrementAndGet();
        createSchema(schema);
        return PostgresEntityStorage.create(dataSourceFor(schema));
    }

    /** Direct DataSource accessor for the schema-assertion test. */
    public static DataSource newDataSourceForFreshSchema() {
        ensureInitialised();
        String schema = "s_" + SCHEMA_RUN_ID + "_" + SCHEMA_COUNTER.incrementAndGet();
        createSchema(schema);
        return dataSourceFor(schema);
    }

    /** True iff {@link #freshStorage()} can be called without an exception. */
    public static boolean isAvailable() {
        try {
            ensureInitialised();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    private static synchronized void ensureInitialised() {
        if (initialised) {
            return;
        }
        String envUrl = System.getenv(ENV_JDBC_URL);
        if (envUrl != null && !envUrl.isBlank()) {
            jdbcUrl = envUrl;
            username = System.getenv().getOrDefault(ENV_USER, "jclaim");
            password = System.getenv().getOrDefault(ENV_PASSWORD, "jclaim");
        } else {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("jclaim")
                    .withUsername("jclaim")
                    .withPassword("jclaim");
            container.start();
            Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "jclaim-pg-stop"));
            jdbcUrl = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
        }
        initialised = true;
    }

    private static void createSchema(String schema) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA " + schema);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create test schema " + schema, ex);
        }
    }

    private static DataSource dataSourceFor(String schema) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl);
        ds.setUser(username);
        ds.setPassword(password);
        ds.setCurrentSchema(schema);
        return ds;
    }
}
