package uk.codery.jclaim.spring.conflict;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.codery.jclaim.event.ConflictEventSink;
import uk.codery.jclaim.event.EntityAttributesConflicted;

/**
 * {@link ConflictEventSink} that emits a single WARN log line per
 * conflict. Selected via {@code jclaim.conflict-sink.type=log}.
 */
public final class LoggingConflictSink implements ConflictEventSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingConflictSink.class);

    @Override
    public void accept(EntityAttributesConflicted event) {
        log.warn("JClaim conflict for {}: {} attribute(s) diverge",
                event.stored().id(), event.differences().size());
    }
}
