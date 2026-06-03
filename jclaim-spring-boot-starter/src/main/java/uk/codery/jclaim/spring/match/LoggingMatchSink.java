package uk.codery.jclaim.spring.match;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.event.MatchAmbiguous;
import uk.codery.jclaim.event.MatchEvent;
import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.event.MatchUndecided;

/**
 * {@link MatchEventSink} that emits a single INFO log line per stewardship
 * event, with structured fields per event variant. Selected via
 * {@code jclaim.match-sink.type=logging}.
 */
public final class LoggingMatchSink implements MatchEventSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingMatchSink.class);

    @Override
    public void accept(MatchEvent event) {
        switch (event) {
            case EntityAttributesConflicted e -> log.info(
                    "JClaim attributes conflicted entity={} differingValues={}",
                    e.stored().id(), e.differingValues().size());
            case MatchUndecided e -> log.info(
                    "JClaim match undecided minted={} candidatesConsidered={} "
                            + "candidatesFound={} candidatePoolTruncated={}",
                    e.minted().id(), e.candidatesConsidered(),
                    e.candidatesFound(), e.candidatePoolTruncated());
            case MatchAmbiguous e -> log.info(
                    "JClaim match ambiguous winner={} otherMatched={} "
                            + "candidatesConsidered={} candidatesFound={} candidatePoolTruncated={}",
                    e.winner().id(), e.otherMatched().size(), e.candidatesConsidered(),
                    e.candidatesFound(), e.candidatePoolTruncated());
        }
    }
}
