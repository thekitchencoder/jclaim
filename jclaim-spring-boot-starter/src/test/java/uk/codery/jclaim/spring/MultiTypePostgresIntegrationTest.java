package uk.codery.jclaim.spring;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.resolver.EntityResolvers;
import uk.codery.jclaim.spring.support.PostgresDockerCondition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end multi-type proof over a real Postgres: schema-per-type +
 * {@link EntityTypeResolverRegistrar} + {@link EntityResolvers} facade compose
 * so that the SAME {@code (source, sourceId)} alias resolves to a DISTINCT
 * canonical entity under each entity type, with each type's data physically
 * isolated in its own Postgres schema.
 */
@ExtendWith(PostgresDockerCondition.class)
@Testcontainers
// Mirror PostgresIntegrationTest: load JclaimAutoConfiguration via
// @ImportAutoConfiguration so it is processed AFTER the user DataSource bean,
// keeping the @ConditionalOnBean(DataSource) wiring order-stable.
@SpringBootTest(classes = MultiTypePostgresIntegrationTest.PgConfig.class)
@ImportAutoConfiguration(JclaimAutoConfiguration.class)
class MultiTypePostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("jclaim.storage.type", () -> "postgres");
        reg.add("jclaim.urn.namespace", () -> "acme");
        // Two entity types; each defaults its Postgres schema to the type key.
        reg.add("jclaim.entity-types.customer.public-id.template", () -> "????-?");
        reg.add("jclaim.entity-types.vehicle.public-id.template", () -> "????-?");
    }

    @Configuration(proxyBeanMethods = false)
    static class PgConfig {
        @Bean
        DataSource dataSource() {
            org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
            ds.setUrl(PG.getJdbcUrl());
            ds.setUser(PG.getUsername());
            ds.setPassword(PG.getPassword());
            return ds;
        }
    }

    @Autowired EntityResolvers resolvers;
    @Autowired DataSource dataSource;

    @Test
    void sameAliasResolvesToDistinctEntitiesIsolatedPerSchema() throws Exception {
        assertThat(resolvers.types()).containsExactlyInAnyOrder("customer", "vehicle");

        EntityResolver customers = resolvers.forType("customer");
        EntityResolver vehicles = resolvers.forType("vehicle");

        SourceSystem source = SourceSystem.of("crm");
        String sharedSourceId = "shared-123";

        // Mint the SAME (source, sourceId) alias under each type.
        Entity customer = mint(customers, source, sharedSourceId);
        Entity vehicle = mint(vehicles, source, sharedSourceId);

        // Distinct canonical ids, each carrying its own type segment.
        assertThat(customer.id().urn()).isNotEqualTo(vehicle.id().urn());
        assertThat(customer.id().type()).isEqualTo("customer");
        assertThat(vehicle.id().type()).isEqualTo("vehicle");
        assertThat(customer.id().namespace()).isEqualTo("acme");
        assertThat(vehicle.id().namespace()).isEqualTo("acme");

        // Each resolver sees only its OWN entity for the shared alias.
        assertThat(customers.findByAlias(source, sharedSourceId))
                .map(e -> e.id().urn()).contains(customer.id().urn());
        assertThat(vehicles.findByAlias(source, sharedSourceId))
                .map(e -> e.id().urn()).contains(vehicle.id().urn());

        // Cross-type URN lookup is rejected by the resolver's URN-ownership
        // guard: getByUrn calls requireOwnUrn first, so a foreign-type URN
        // (whose type segment differs from the resolver's) throws
        // IllegalArgumentException BEFORE any storage query — proving the guard,
        // not schema isolation (the row-count + findByAlias assertions below
        // prove the physical schema isolation).
        EntityId vehicleUrn = vehicle.id();
        assertThatThrownBy(() -> customers.getByUrn(vehicleUrn))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to");
        EntityId customerUrn = customer.id();
        assertThatThrownBy(() -> vehicles.getByUrn(customerUrn))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to");

        // Each resolver CAN see its own entity by URN.
        assertThat(customers.getByUrn(customerUrn).id().urn()).isEqualTo(customer.id().urn());
        assertThat(vehicles.getByUrn(vehicleUrn).id().urn()).isEqualTo(vehicle.id().urn());

        // Both per-type schemas physically exist in the database.
        Set<String> schemas = schemaNames();
        assertThat(schemas).contains("customer", "vehicle");

        // And each schema's entities table holds exactly one row.
        assertThat(entityRowCount("customer")).isEqualTo(1);
        assertThat(entityRowCount("vehicle")).isEqualTo(1);
    }

    private static Entity mint(EntityResolver resolver, SourceSystem source, String sourceId) {
        ResolutionResult result = resolver.resolveOrMint(new Claim(source, sourceId, List.of()));
        assertThat(result).isInstanceOf(ResolutionResult.Minted.class);
        return ((ResolutionResult.Minted) result).entity();
    }

    private Set<String> schemaNames() throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             ResultSet rs = c.getMetaData().getSchemas()) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_SCHEM"));
            }
        }
        return names;
    }

    private int entityRowCount(String schema) throws Exception {
        try (Connection c = dataSource.getConnection();
             var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM \"" + schema + "\".entities")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
