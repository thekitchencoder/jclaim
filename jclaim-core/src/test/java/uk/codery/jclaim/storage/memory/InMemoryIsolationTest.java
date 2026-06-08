package uk.codery.jclaim.storage.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.DefaultEntityResolver;

/**
 * Pins instance-per-type isolation for the in-memory adapter: two resolvers of
 * different entity types over their own {@link InMemoryEntityStorage} share no
 * state, so the same {@code (source, sourceId)} alias and the same publicId
 * resolve independently per type.
 */
final class InMemoryIsolationTest {

    private static final SourceSystem CRM = SourceSystem.of("crm");

    private static DefaultEntityResolver resolverFor(String type) {
        return DefaultEntityResolver.builder(new InMemoryEntityStorage())
                .namespace("acme")
                .entityType(type)
                .publicIdTemplate("????-????-?")
                .build();
    }

    private static Claim claim(String sourceId) {
        return new Claim(CRM, sourceId, List.of());
    }

    @Test
    void sameAliasResolvesIndependentlyPerType() {
        DefaultEntityResolver customers = resolverFor("customer");
        DefaultEntityResolver vehicles = resolverFor("vehicle");

        Entity customer = customers.resolveOrMint(claim("ABC-123")).entity();
        Entity vehicle = vehicles.resolveOrMint(claim("ABC-123")).entity();

        // Independent identities: the shared alias mints a distinct entity per type.
        assertThat(customer.id()).isNotEqualTo(vehicle.id());
        assertThat(customer.id().type()).isEqualTo("customer");
        assertThat(vehicle.id().type()).isEqualTo("vehicle");

        // Each resolver only sees its own entity under that alias (real persisted read).
        assertThat(customers.findByAlias(CRM, "ABC-123"))
                .map(Entity::id)
                .contains(customer.id());
        assertThat(vehicles.findByAlias(CRM, "ABC-123"))
                .map(Entity::id)
                .contains(vehicle.id());
    }

    @Test
    void publicIdMintedUnderOneTypeInvisibleToOther() {
        DefaultEntityResolver customers = resolverFor("customer");
        DefaultEntityResolver vehicles = resolverFor("vehicle");

        Entity customer = customers.resolveOrMint(claim("ABC-123")).entity();
        assertThat(customer.publicId()).isNotNull();

        // The customer's publicId is visible to its own type but not the other.
        assertThat(customers.findByPublicId(customer.publicId()))
                .map(Entity::id)
                .contains(customer.id());
        assertThat(vehicles.findByPublicId(customer.publicId())).isEmpty();
    }
}
