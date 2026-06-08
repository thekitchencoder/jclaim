package uk.codery.jclaim.storage.postgres;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.storage.postgres.support.PostgresTestSupport;
import uk.codery.jclaim.storage.postgres.support.RequiresDockerCondition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the adapter's SQL error-translation path: a JDBC failure must
 * surface as a {@link PostgresStorageException}, not a raw {@code SQLException}.
 */
@ExtendWith(RequiresDockerCondition.class)
final class PostgresEntityStorageErrorTest {

    @Test
    void findByPublicId_wrapsSqlErrorAsPostgresStorageException() throws Exception {
        DataSource ds = PostgresTestSupport.newDataSourceForFreshSchema();
        PostgresEntityStorage storage = PostgresEntityStorage.create(ds); // applies schema
        // Pull the table out from under the adapter so the next query fails.
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE entities CASCADE");
        }
        assertThatThrownBy(() -> storage.findByPublicId("ANY-VALUE-0"))
                .isInstanceOf(PostgresStorageException.class);
    }
}
