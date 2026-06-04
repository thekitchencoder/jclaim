package uk.codery.jclaim.resolver;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityResolversTest {

    private static EntityResolver resolverFor(String type) {
        return DefaultEntityResolver.builder(new InMemoryEntityStorage())
                .namespace("acme").entityType(type).build();
    }

    @Test
    void types_returnsAllKeys() {
        EntityResolvers resolvers = EntityResolvers.of(Map.of(
                "customer", resolverFor("customer"),
                "vehicle", resolverFor("vehicle")));

        assertThat(resolvers.types()).containsExactlyInAnyOrder("customer", "vehicle");
    }

    @Test
    void find_returnsResolverWhenPresent() {
        EntityResolver customer = resolverFor("customer");
        EntityResolvers resolvers = EntityResolvers.of(Map.of("customer", customer));

        assertThat(resolvers.find("customer")).contains(customer);
    }

    @Test
    void find_returnsEmptyWhenAbsent() {
        EntityResolvers resolvers = EntityResolvers.of(Map.of("customer", resolverFor("customer")));

        assertThat(resolvers.find("vehicle")).isEmpty();
    }

    @Test
    void of_rejectsBlankKey() {
        assertThatThrownBy(() -> EntityResolvers.of(Map.of("  ", resolverFor("customer"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsNullResolver() {
        Map<String, EntityResolver> source = new HashMap<>();
        source.put("customer", null);
        assertThatThrownBy(() -> EntityResolvers.of(source))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void forType_returnsResolver() {
        EntityResolver customer = resolverFor("customer");
        EntityResolvers resolvers = EntityResolvers.of(Map.of("customer", customer));

        assertThat(resolvers.forType("customer")).isSameAs(customer);
    }

    @Test
    void forType_unknownThrowsListingKnownTypes() {
        EntityResolvers resolvers = EntityResolvers.of(Map.of(
                "customer", resolverFor("customer"),
                "vehicle", resolverFor("vehicle")));

        assertThatThrownBy(() -> resolvers.forType("supplier"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supplier")
                .hasMessageContaining("customer")
                .hasMessageContaining("vehicle");
    }

    @Test
    void of_isDefensivelyCopied() {
        Map<String, EntityResolver> source = new HashMap<>();
        source.put("customer", resolverFor("customer"));
        EntityResolvers resolvers = EntityResolvers.of(source);

        source.put("vehicle", resolverFor("vehicle"));

        assertThat(resolvers.types()).containsExactly("customer");
    }
}
