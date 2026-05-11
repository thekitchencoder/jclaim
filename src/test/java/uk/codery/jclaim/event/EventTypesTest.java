package uk.codery.jclaim.event;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTypesTest {

    @Test
    void attributeDiff_rejectsBlankName() {
        assertThatThrownBy(() -> new AttributeDiff(" ", "a", "b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entityAttributesConflicted_rejectsEmptyDifferences() {
        Entity entity = new Entity(
                EntityId.of(UUID.fromString("01900000-0000-7000-8000-000000000030")),
                "0000-0000-0", List.of(), List.of(), null,
                Instant.EPOCH, Instant.EPOCH);
        Claim claim = new Claim(SourceSystem.of("crm"), "id-1",
                List.of(MatchingAttribute.of("email", "e@example.com")));

        assertThatThrownBy(() -> new EntityAttributesConflicted(
                entity, claim, List.of(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conflictEventSink_noopDoesNotThrow() {
        ConflictEventSink sink = ConflictEventSink.noop();
        Entity entity = new Entity(
                EntityId.of(UUID.fromString("01900000-0000-7000-8000-000000000031")),
                "0000-0000-0", List.of(), List.of(), null,
                Instant.EPOCH, Instant.EPOCH);
        Claim claim = new Claim(SourceSystem.of("crm"), "id-1", List.of());
        EntityAttributesConflicted event = new EntityAttributesConflicted(
                entity, claim,
                List.of(new AttributeDiff("k", null, "v")),
                Instant.EPOCH);

        sink.accept(event);  // must not throw
        assertThat(event.differences()).hasSize(1);
    }
}
