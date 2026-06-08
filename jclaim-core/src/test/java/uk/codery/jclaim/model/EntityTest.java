package uk.codery.jclaim.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityTest {

    @Test
    void allowsNullPublicId() {
        Entity e = new Entity(EntityId.of(UUID.randomUUID()), null,
                List.of(), List.of(), null, Instant.EPOCH, Instant.EPOCH);
        assertThat(e.publicId()).isNull();
    }

    @Test
    void rejectsBlankPublicIdWhenPresent() {
        assertThatThrownBy(() -> new Entity(EntityId.of(UUID.randomUUID()), "  ",
                List.of(), List.of(), null, Instant.EPOCH, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsPresentPublicId() {
        Entity e = new Entity(EntityId.of(UUID.randomUUID()), "K7M2-9X4P-N",
                List.of(), List.of(), null, Instant.EPOCH, Instant.EPOCH);
        assertThat(e.publicId()).isEqualTo("K7M2-9X4P-N");
    }
}
