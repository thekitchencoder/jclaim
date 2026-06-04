package uk.codery.jclaim.storage.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.storage.mongo.support.MongoTestSupport;
import uk.codery.jclaim.storage.mongo.support.RequiresDockerCondition;

/**
 * Pins collection-per-type isolation for the MongoDB adapter: two resolvers of
 * different entity types, each over its own collection on the <em>same</em>
 * client and database, share no state. The same {@code (source, sourceId)}
 * alias and the same humanId resolve independently per collection — isolation
 * is physical (distinct collections), not logical.
 */
@ExtendWith(RequiresDockerCondition.class)
final class MongoIsolationTest {

    private static final SourceSystem CRM = SourceSystem.of("crm");

    private MongoEntityStorage customerStorage;
    private MongoEntityStorage vehicleStorage;
    private DefaultEntityResolver customers;
    private DefaultEntityResolver vehicles;

    private void wire() {
        // Distinct collections handed out by the SAME shared client/database.
        customerStorage = MongoEntityStorage.create(MongoTestSupport.newCollection());
        vehicleStorage = MongoEntityStorage.create(MongoTestSupport.newCollection());
        customers = resolverFor("customer", customerStorage);
        vehicles = resolverFor("vehicle", vehicleStorage);
    }

    private static DefaultEntityResolver resolverFor(String type, MongoEntityStorage storage) {
        return DefaultEntityResolver.builder(storage)
                .namespace("acme")
                .entityType(type)
                .humanIdTemplate("????-????-?")
                .build();
    }

    private static Claim claim(String sourceId) {
        return new Claim(CRM, sourceId, List.of());
    }

    @Test
    void sameAliasIndependentAcrossCollections() {
        wire();

        Entity customer = customers.resolveOrMint(claim("ABC-123")).entity();
        Entity vehicle = vehicles.resolveOrMint(claim("ABC-123")).entity();

        // Shared alias mints a distinct entity per collection.
        assertThat(customer.id()).isNotEqualTo(vehicle.id());
        assertThat(customer.id().type()).isEqualTo("customer");
        assertThat(vehicle.id().type()).isEqualTo("vehicle");

        // Each collection persists and returns only its own entity for the alias.
        Alias alias = Alias.of(CRM, "ABC-123");
        assertThat(customerStorage.findByAlias(alias))
                .map(Entity::id)
                .contains(customer.id());
        assertThat(vehicleStorage.findByAlias(alias))
                .map(Entity::id)
                .contains(vehicle.id());
    }

    @Test
    void humanIdScopedPerCollection() {
        wire();

        Entity customer = customers.resolveOrMint(claim("ABC-123")).entity();
        assertThat(customer.humanId()).isNotNull();

        // The customer's humanId is found in its own collection but not the other.
        assertThat(customerStorage.findByHumanId(customer.humanId()))
                .map(Entity::id)
                .contains(customer.id());
        assertThat(vehicleStorage.findByHumanId(customer.humanId())).isEmpty();
    }
}
