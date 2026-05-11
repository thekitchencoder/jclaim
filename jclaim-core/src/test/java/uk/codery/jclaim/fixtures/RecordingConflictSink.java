package uk.codery.jclaim.fixtures;

import uk.codery.jclaim.event.ConflictEventSink;
import uk.codery.jclaim.event.EntityAttributesConflicted;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test-scope {@link ConflictEventSink} that records every emitted event.
 * Shared across the corpus integration tests so each test class doesn't
 * roll its own inner-class recorder.
 */
public final class RecordingConflictSink implements ConflictEventSink {

    private final List<EntityAttributesConflicted> events = new ArrayList<>();

    @Override
    public void accept(EntityAttributesConflicted event) {
        events.add(event);
    }

    /** Snapshot of events received so far. */
    public List<EntityAttributesConflicted> events() {
        return Collections.unmodifiableList(events);
    }
}
