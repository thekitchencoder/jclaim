package uk.codery.jclaim.storage.postgres;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.storage.postgres.support.PostgresTestSupport;
import uk.codery.jclaim.storage.postgres.support.RequiresDockerCondition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the adapter's auto-applied schema lands exactly the tables, indexes
 * and constraints the port relies on. Catches drift between
 * {@code schema.sql}, the documented schema, and the adapter's runtime
 * assumptions.
 */
@ExtendWith(RequiresDockerCondition.class)
final class PostgresSchemaTest {

    @Test
    void applySchema_createsAllExpectedRelationsAndConstraints() throws SQLException {
        DataSource ds = PostgresTestSupport.newDataSourceForFreshSchema();
        PostgresEntityStorage.create(ds); // applies schema as a side-effect

        try (Connection conn = ds.getConnection()) {
            Map<String, Set<String>> tableColumns = readTableColumns(conn);
            assertThat(tableColumns.keySet())
                    .containsExactlyInAnyOrder("entities", "entity_aliases", "entity_attributes");

            assertThat(tableColumns.get("entities"))
                    .containsExactlyInAnyOrder("urn", "human_id", "superseded_by", "created_at", "updated_at");
            assertThat(tableColumns.get("entity_aliases"))
                    .containsExactlyInAnyOrder("source", "source_id", "entity_urn", "attached_at", "position");
            assertThat(tableColumns.get("entity_attributes"))
                    .containsExactlyInAnyOrder("entity_urn", "name", "value", "position");

            // human_id is opt-in: nullable, so its uniqueness is enforced by a
            // partial unique index (WHERE human_id IS NOT NULL) rather than an
            // inline UNIQUE constraint. The table-constraint list therefore
            // covers only urn / alias / attribute keys.
            List<String> uniqueConstraints = readUniqueOrPrimaryConstraintColumns(conn);
            assertThat(uniqueConstraints).contains(
                    "entities(urn)",
                    "entity_aliases(source,source_id)",
                    "entity_attributes(entity_urn,name)");
            assertThat(uniqueConstraints)
                    .noneMatch(s -> s.equals("entities(human_id)"));

            List<String> indexes = readNonConstraintIndexes(conn);
            assertThat(indexes)
                    .anyMatch(s -> s.contains("entities_human_id_unique"))
                    .anyMatch(s -> s.contains("idx_entity_aliases_entity_urn"))
                    .anyMatch(s -> s.contains("idx_entity_attributes_entity_urn"))
                    .anyMatch(s -> s.contains("idx_entity_attributes_name_value"));

            // The human_id uniqueness index must be partial, otherwise multiple
            // entities without a humanId would collide on NULL.
            assertThat(readPartialUniqueIndexDefinition(conn, "entities_human_id_unique"))
                    .isNotNull()
                    .containsIgnoringCase("UNIQUE")
                    .containsIgnoringCase("human_id IS NOT NULL");
        }
    }

    private static Map<String, Set<String>> readTableColumns(Connection conn) throws SQLException {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT table_name, column_name FROM information_schema.columns "
                             + "WHERE table_schema = current_schema()")) {
            while (rs.next()) {
                result.computeIfAbsent(rs.getString(1), k -> new java.util.HashSet<>())
                        .add(rs.getString(2));
            }
        }
        return result;
    }

    private static List<String> readUniqueOrPrimaryConstraintColumns(Connection conn) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT tc.table_name, "
                             + "string_agg(kcu.column_name, ',' ORDER BY kcu.ordinal_position) "
                             + "FROM information_schema.table_constraints tc "
                             + "JOIN information_schema.key_column_usage kcu "
                             + "  ON tc.constraint_name = kcu.constraint_name "
                             + " AND tc.table_schema = kcu.table_schema "
                             + "WHERE tc.table_schema = current_schema() "
                             + "  AND tc.constraint_type IN ('PRIMARY KEY','UNIQUE') "
                             + "GROUP BY tc.table_name, tc.constraint_name")) {
            while (rs.next()) {
                result.add(rs.getString(1) + "(" + rs.getString(2) + ")");
            }
        }
        return result;
    }

    private static String readPartialUniqueIndexDefinition(Connection conn, String indexName)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE schemaname = current_schema() AND indexname = ?")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static List<String> readNonConstraintIndexes(Connection conn) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT indexname FROM pg_indexes "
                             + "WHERE schemaname = current_schema()")) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }
}
