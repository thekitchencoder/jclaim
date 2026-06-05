package uk.codery.jclaim.storage.postgres;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Validation-only coverage for the {@code schema(String)} builder param. These
 * cases never touch the database — {@code applySchema(false)} keeps construction
 * offline — so they run without Docker. Docker-gated isolation coverage lives in
 * {@link PostgresSchemaIsolationTest}.
 */
final class PostgresSchemaScopeTest {

    private static PGSimpleDataSource dummyDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://localhost:5432/unused");
        ds.setUser("unused");
        ds.setPassword("unused");
        return ds;
    }

    @Test
    void builder_rejectsIllegalSchemaIdentifier() {
        PGSimpleDataSource ds = dummyDataSource();
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PostgresEntityStorage.builder(ds)
                        .applySchema(false)
                        .schema("bad schema; DROP TABLE")
                        .build())
                .withMessageContaining("schema");
    }

    @Test
    void builder_acceptsNullSchemaAsDefault() {
        PGSimpleDataSource ds = dummyDataSource();
        assertThatNoException().isThrownBy(() -> {
            PostgresEntityStorage storage = PostgresEntityStorage.builder(ds)
                    .applySchema(false)
                    .schema(null)
                    .build();
            assertThat(storage).isNotNull();
        });
    }

    /**
     * A blank schema string is treated as unscoped — the same as {@code null} —
     * and must NOT reach the identifier validator. If blank were routed down the
     * validate-then-scope branch instead of the unscoped else-branch,
     * {@code requireValidSegment} would reject the empty segment and this would
     * throw (as {@link #builder_rejectsIllegalSchemaIdentifier()} shows a real
     * bad value does).
     */
    @Test
    void builder_treatsBlankSchemaAsUnscoped() {
        PGSimpleDataSource ds = dummyDataSource();
        assertThatNoException().isThrownBy(() -> {
            PostgresEntityStorage storage = PostgresEntityStorage.builder(ds)
                    .applySchema(false)
                    .schema("   ")
                    .build();
            assertThat(storage).isNotNull();
        });
    }
}
