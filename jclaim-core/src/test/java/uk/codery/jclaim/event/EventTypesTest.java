package uk.codery.jclaim.event;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTypesTest {

    private static Entity entity(String uuid) {
        return new Entity(
                EntityId.of(UUID.fromString(uuid)),
                "0000-0000-0", List.of(), List.of(), null,
                Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void attributeDiff_rejectsBlankName() {
        assertThatThrownBy(() -> new AttributeDiff(" ", "a", "b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entityAttributesConflicted_rejectsEmptyDifferences() {
        Entity entity = entity("01900000-0000-7000-8000-000000000030");
        Claim claim = new Claim(SourceSystem.of("crm"), "id-1",
                List.of(MatchingAttribute.of("email", "e@example.com")));

        assertThatThrownBy(() -> new EntityAttributesConflicted(entity, claim, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entityAttributesConflicted_rejectsNulls() {
        Entity entity = entity("01900000-0000-7000-8000-000000000033");
        Claim claim = new Claim(SourceSystem.of("crm"), "id-1", List.of());
        List<AttributeDiff> diffs = List.of(new AttributeDiff("k", "a", "b"));

        assertThatThrownBy(() -> new EntityAttributesConflicted(null, claim, diffs))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EntityAttributesConflicted(entity, null, diffs))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EntityAttributesConflicted(entity, claim, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void entityAttributesConflicted_defensivelyCopiesDifferences() {
        Entity entity = entity("01900000-0000-7000-8000-000000000034");
        Claim claim = new Claim(SourceSystem.of("crm"), "id-1", List.of());
        List<AttributeDiff> diffs = new ArrayList<>();
        diffs.add(new AttributeDiff("k", "a", "b"));

        EntityAttributesConflicted event = new EntityAttributesConflicted(entity, claim, diffs);
        diffs.add(new AttributeDiff("k2", "c", "d"));

        assertThat(event.differingValues()).hasSize(1);
    }

    @Test
    void matchEventSink_noopDoesNotThrow() {
        MatchEventSink sink = MatchEventSink.noop();
        Entity entity = entity("01900000-0000-7000-8000-000000000031");
        Claim claim = new Claim(SourceSystem.of("crm"), "id-1", List.of());
        MatchEvent event = new EntityAttributesConflicted(
                entity, claim, List.of(new AttributeDiff("k", "x", "v")));

        sink.accept(event);  // must not throw
        assertThat(((EntityAttributesConflicted) event).differingValues()).hasSize(1);
    }
}
