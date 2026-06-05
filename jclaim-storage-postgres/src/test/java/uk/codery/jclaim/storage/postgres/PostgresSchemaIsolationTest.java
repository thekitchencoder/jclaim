package uk.codery.jclaim.storage.postgres;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.storage.StorageOutcome;
import uk.codery.jclaim.storage.postgres.support.PostgresTestSupport;
import uk.codery.jclaim.storage.postgres.support.RequiresDockerCondition;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that two {@link PostgresEntityStorage} instances scoped to different
 * Postgres schemas over a single shared {@link DataSource} are fully isolated:
 * the SAME alias persisted under each schema mints a distinct entity, and each
 * storage reads back only its own — no cross-schema unique-violation collision.
 */
@ExtendWith(RequiresDockerCondition.class)
final class PostgresSchemaIsolationTest {

    @Test
    void twoSchemasOverOneDataSource_areFullyIsolated() {
        DataSource shared = PostgresTestSupport.sharedDataSource();
        // Schema names must satisfy the URN-segment grammar [A-Za-z0-9][A-Za-z0-9-]*
        // (no underscores), so join with hyphens.
        String run = Long.toHexString(System.nanoTime());
        String customerSchema = "customer-" + run;
        String vehicleSchema = "vehicle-" + run;

        PostgresEntityStorage customer = PostgresEntityStorage.builder(shared)
                .schema(customerSchema)
                .build();
        PostgresEntityStorage vehicle = PostgresEntityStorage.builder(shared)
                .schema(vehicleSchema)
                .build();

        Alias alias = Alias.of(SourceSystem.of("crm"), "shared-key");

        Entity customerEntity = mint(alias, "00000000-0000-7000-8000-000000000001");
        Entity vehicleEntity = mint(alias, "00000000-0000-7000-8000-000000000002");

        StorageOutcome customerOutcome = customer.resolveOrCreate(alias, () -> customerEntity);
        StorageOutcome vehicleOutcome = vehicle.resolveOrCreate(alias, () -> vehicleEntity);

        // Both are fresh Creates — neither write saw the other's alias.
        assertThat(customerOutcome).isInstanceOf(StorageOutcome.Created.class);
        assertThat(vehicleOutcome).isInstanceOf(StorageOutcome.Created.class);

        // Each storage reads back ONLY its own entity for the shared alias.
        EntityId customerSeen = customer.findByAlias(alias).map(Entity::id).orElseThrow();
        EntityId vehicleSeen = vehicle.findByAlias(alias).map(Entity::id).orElseThrow();

        assertThat(customerSeen).isEqualTo(customerEntity.id());
        assertThat(vehicleSeen).isEqualTo(vehicleEntity.id());
        // The two schemas are isolated: neither read leaked the other's id.
        assertThat(customerSeen).isNotEqualTo(vehicleSeen);

        // findByUrn is schema-scoped too: each storage finds its own entity by URN,
        // and does NOT see the other schema's entity even by its exact URN.
        assertThat(customer.findByUrn(customerEntity.id())).isPresent();
        assertThat(vehicle.findByUrn(vehicleEntity.id())).isPresent();
        assertThat(customer.findByUrn(vehicleEntity.id())).isEmpty();
        assertThat(vehicle.findByUrn(customerEntity.id())).isEmpty();
    }

    private static Entity mint(Alias alias, String uuid) {
        Instant now = Instant.now();
        return new Entity(
                EntityId.of(UUID.fromString(uuid)),
                null,
                List.of(alias),
                List.of(),
                null,
                now,
                now);
    }
}
