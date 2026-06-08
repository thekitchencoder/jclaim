package uk.codery.jclaim.event;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.matching.TriState;
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

    @Test
    void candidateOutcome_rejectsNulls() {
        Entity entity = entity("01900000-0000-7000-8000-000000000035");
        assertThatThrownBy(() -> new CandidateOutcome(null, TriState.MATCHED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CandidateOutcome(entity, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void matchUndecided_defensivelyCopiesCandidatesAndRejectsNulls() {
        Entity minted = entity("01900000-0000-7000-8000-000000000036");
        Entity candidate = entity("01900000-0000-7000-8000-000000000037");
        Claim claim = new Claim(SourceSystem.of("crm"), "id-1", List.of());
        List<CandidateOutcome> outcomes = new ArrayList<>();
        outcomes.add(new CandidateOutcome(candidate, TriState.UNDETERMINED));

        MatchUndecided event = new MatchUndecided(claim, minted, outcomes, 1, 1);
        outcomes.clear();

        assertThat(event.candidates()).hasSize(1);
        assertThat(event.candidatesConsidered()).isEqualTo(1);
        assertThatThrownBy(() -> new MatchUndecided(null, minted, List.of(), 0, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void matchAmbiguous_defensivelyCopiesAndRejectsNulls() {
        Entity winner = entity("01900000-0000-7000-8000-000000000038");
        Entity other = entity("01900000-0000-7000-8000-000000000039");
        Claim claim = new Claim(SourceSystem.of("crm"), "id-1", List.of());
        List<Entity> others = new ArrayList<>(List.of(other));
        List<CandidateOutcome> outcomes = new ArrayList<>(List.of(
                new CandidateOutcome(winner, TriState.MATCHED),
                new CandidateOutcome(other, TriState.MATCHED)));

        MatchAmbiguous event = new MatchAmbiguous(claim, winner, others, outcomes, 2, 2);
        others.clear();
        outcomes.clear();

        assertThat(event.otherMatched()).containsExactly(other);
        assertThat(event.candidates()).hasSize(2);
        assertThat(event.candidatesConsidered()).isEqualTo(2);
        assertThat(event.candidatesFound()).isEqualTo(2);
        assertThatThrownBy(() -> new MatchAmbiguous(claim, null, List.of(), List.of(), 0, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void candidatePoolTruncated_carriesClaimAndCap_andRejectsNullClaim() {
        Claim claim = new Claim(SourceSystem.of("crm"), "id-1", List.of());
        CandidatePoolTruncated event = new CandidatePoolTruncated(claim, 7);
        assertThat(event.claim()).isEqualTo(claim);
        assertThat(event.cap()).isEqualTo(7);
        assertThatThrownBy(() -> new CandidatePoolTruncated(null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CandidatePoolTruncated(claim, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandidatePoolTruncated(claim, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
