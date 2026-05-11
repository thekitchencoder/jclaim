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
        assertThatThrownBy(() -> new EntityId("urn:codery:something:01900000-0000-7000-8000-000000000004"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityId("urn:codery:entity:not-a-uuid"))
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
