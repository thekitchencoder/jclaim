package uk.codery.jclaim.spring.match;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import uk.codery.jclaim.event.AttributeDiff;
import uk.codery.jclaim.event.CandidatePoolTruncated;
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

    // -- LoggingMatchSink: all four switch arms ------------------------------
    //
    // Each arm is expected to emit exactly one INFO log line carrying the
    // structured fields for that variant. We capture the LoggingMatchSink
    // logger's output via a Logback ListAppender and assert on the formatted
    // message so the test fails if an arm stops logging or logs the wrong
    // fields (the no-exception check alone is near-vacuous given the
    // exhaustive sealed switch).

    private Logger sinkLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        sinkLogger = (Logger) LoggerFactory.getLogger(LoggingMatchSink.class);
        appender = new ListAppender<>();
        appender.start();
        sinkLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        sinkLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void loggingSinkHandlesConflicted() {
        MatchEvent event = new EntityAttributesConflicted(
                entity(), claim(),
                List.of(new AttributeDiff("email", "a@x", "b@x")));

        new LoggingMatchSink().accept(event);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent logged = appender.list.get(0);
        assertThat(logged.getLevel()).isEqualTo(Level.INFO);
        assertThat(logged.getFormattedMessage())
                .contains("attributes conflicted")
                // one differing value => the conflicting count surfaces
                .contains("differingValues=1");
    }

    @Test
    void loggingSinkHandlesUndecided() {
        MatchEvent event = new MatchUndecided(claim(), entity(), List.of(), 3, 5);

        new LoggingMatchSink().accept(event);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent logged = appender.list.get(0);
        assertThat(logged.getLevel()).isEqualTo(Level.INFO);
        assertThat(logged.getFormattedMessage())
                .contains("match undecided")
                .contains("candidatesConsidered=3")
                .contains("candidatesFound=5");
    }

    @Test
    void loggingSinkHandlesAmbiguous() {
        MatchEvent event = new MatchAmbiguous(
                claim(), entity(), List.of(entity()), List.of(), 2, 4);

        new LoggingMatchSink().accept(event);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent logged = appender.list.get(0);
        assertThat(logged.getLevel()).isEqualTo(Level.INFO);
        assertThat(logged.getFormattedMessage())
                .contains("match ambiguous")
                .contains("otherMatched=1")
                .contains("candidatesConsidered=2")
                .contains("candidatesFound=4");
    }

    @Test
    void loggingSinkHandlesPoolTruncated() {
        MatchEvent event = new CandidatePoolTruncated(claim(), 1);

        new LoggingMatchSink().accept(event);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent logged = appender.list.get(0);
        assertThat(logged.getLevel()).isEqualTo(Level.INFO);
        assertThat(logged.getFormattedMessage())
                .contains("candidate pool truncated")
                .contains("claim=")
                .contains("cap=1");
    }

    // -- SpringEventMatchSink + JclaimMatchEvent -----------------------------

    @Test
    void springEventSinkPublishesWrappedEvent() {
        MatchEvent payload = new MatchUndecided(claim(), entity(), List.of(), 0, 0);
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
