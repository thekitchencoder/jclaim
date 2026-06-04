package uk.codery.jclaim.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityIdTest {

    @Test
    void of_buildsUrnUnderGivenNamespace() {
        UUID uuid = UUID.fromString("01900000-0000-7000-8000-000000000001");
        EntityId id = EntityId.of("retail", uuid);

        assertThat(id.urn()).isEqualTo("urn:retail:entity:" + uuid);
        assertThat(id.namespace()).isEqualTo("retail");
        assertThat(id.uuid()).isEqualTo(uuid);
    }

    @Test
    void of_singleArg_usesDefaultNamespace() {
        UUID uuid = UUID.fromString("01900000-0000-7000-8000-000000000002");
        EntityId id = EntityId.of(uuid);

        assertThat(id.namespace()).isEqualTo(EntityId.DEFAULT_NAMESPACE);
        assertThat(id.urn()).isEqualTo("urn:codery:entity:" + uuid);
    }

    @Test
    void toString_returnsUrn() {
        UUID uuid = UUID.fromString("01900000-0000-7000-8000-000000000003");
        assertThat(EntityId.of(uuid).toString()).isEqualTo("urn:codery:entity:" + uuid);
    }

    @Test
    void constructor_rejectsMalformedUrn() {
        assertThatThrownBy(() -> new EntityId("not a urn"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityId("urn:codery:entity:not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsUrnWithExplicitType() {
        UUID id = UUID.fromString("018f0000-0000-7000-8000-000000000000");
        EntityId e = EntityId.of("acme", "customer", id);
        assertThat(e.urn()).isEqualTo("urn:acme:customer:" + id);
        assertThat(e.namespace()).isEqualTo("acme");
        assertThat(e.type()).isEqualTo("customer");
        assertThat(e.uuid()).isEqualTo(id);
    }

    @Test
    void twoArgFactoryDefaultsTypeToEntity() {
        UUID id = UUID.fromString("018f0000-0000-7000-8000-000000000000");
        EntityId e = EntityId.of("acme", id);
        assertThat(e.urn()).isEqualTo("urn:acme:entity:" + id);
        assertThat(e.type()).isEqualTo("entity");
    }

    @Test
    void existingEntityUrnStillParses() {
        EntityId e = new EntityId("urn:codery:entity:018f0000-0000-7000-8000-000000000000");
        assertThat(e.namespace()).isEqualTo("codery");
        assertThat(e.type()).isEqualTo("entity");
    }

    @Test
    void rejectsBlankType() {
        UUID id = UUID.fromString("018f0000-0000-7000-8000-000000000000");
        assertThatThrownBy(() -> EntityId.of("acme", "  ", id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsBlankNamespace() {
        UUID uuid = UUID.fromString("01900000-0000-7000-8000-000000000005");
        assertThatThrownBy(() -> EntityId.of(" ", uuid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equality_followsRecordSemantics() {
        UUID uuid = UUID.fromString("01900000-0000-7000-8000-000000000006");
        assertThat(EntityId.of(uuid)).isEqualTo(EntityId.of("codery", uuid));
        assertThat(EntityId.of("a", uuid)).isNotEqualTo(EntityId.of("b", uuid));
    }
}
