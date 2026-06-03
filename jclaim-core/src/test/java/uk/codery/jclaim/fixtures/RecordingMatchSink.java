package uk.codery.jclaim.fixtures;

import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.event.MatchEvent;
import uk.codery.jclaim.event.MatchEventSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test-scope {@link MatchEventSink} that records every emitted
 * {@link EntityAttributesConflicted} event. Shared across the corpus
 * integration tests so each test class doesn't roll its own inner-class
 * recorder. Non-conflict stewardship events are not produced by the default
 * alias-only resolver these tests exercise.
 */
public final class RecordingMatchSink implements MatchEventSink {

    private final List<EntityAttributesConflicted> events = new ArrayList<>();

    @Override
    public void accept(MatchEvent event) {
        if (event instanceof EntityAttributesConflicted conflict) {
            events.add(conflict);
        }
    }

    /** Snapshot of conflict events received so far. */
    public List<EntityAttributesConflicted> events() {
        return Collections.unmodifiableList(events);
    }
}
