package uk.codery.jclaim.spring.match;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import uk.codery.jclaim.event.AttributeDiff;
import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.event.MatchAmbiguous;
import uk.codery.jclaim.event.MatchEvent;
import uk.codery.jclaim.event.MatchUndecided;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.SourceSystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the {@code match} package sinks: every
 * {@link LoggingMatchSink} switch arm, the {@link SpringEventMatchSink}
 * bridge, and the {@link JclaimMatchEvent} payload wrapper.
 */
class MatchSinkUnitTest {

    private static Entity entity() {
        EntityId id = EntityId.of("acme", "customer", UUID.randomUUID());
        Instant now = Instant.now();
        return new Entity(id, null, List.of(), List.of(), null, now, now);
    }

    private static Claim claim() {
        return new Claim(SourceSystem.of("crm"), "u-1", List.of());
    }

    // -- LoggingMatchSink: all three switch arms -----------------------------

    @Test
    void loggingSinkHandlesConflicted() {
        MatchEvent event = new EntityAttributesConflicted(
                entity(), claim(),
                List.of(new AttributeDiff("email", "a@x", "b@x")));
        // No exception => the EntityAttributesConflicted arm executed.
        new LoggingMatchSink().accept(event);
    }

    @Test
    void loggingSinkHandlesUndecided() {
        MatchEvent event = new MatchUndecided(claim(), entity(), List.of(), 0, 0, false);
        new LoggingMatchSink().accept(event);
    }

    @Test
    void loggingSinkHandlesAmbiguous() {
        MatchEvent event = new MatchAmbiguous(
                claim(), entity(), List.of(entity()), List.of(), 2, 2, true);
        new LoggingMatchSink().accept(event);
    }

    // -- SpringEventMatchSink + JclaimMatchEvent -----------------------------

    @Test
    void springEventSinkPublishesWrappedEvent() {
        MatchEvent payload = new MatchUndecided(claim(), entity(), List.of(), 0, 0, false);
        var captured = new java.util.concurrent.atomic.AtomicReference<Object>();
        ApplicationEventPublisher publisher = captured::set;

        new SpringEventMatchSink(publisher).accept(payload);

        assertThat(captured.get()).isInstanceOf(JclaimMatchEvent.class);
        JclaimMatchEvent wrapper = (JclaimMatchEvent) captured.get();
        assertThat(wrapper.payload()).isSameAs(payload);
        assertThat(wrapper.getSource()).isNotNull();
    }

    @Test
    void springEventSinkRejectsNullPublisher() {
        assertThatThrownBy(() -> new SpringEventMatchSink(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("publisher");
    }

    @Test
    void jclaimMatchEventRejectsNullPayload() {
        assertThatThrownBy(() -> new JclaimMatchEvent(this, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }
}
