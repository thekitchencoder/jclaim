package uk.codery.jclaim.spring.match;

import org.springframework.context.ApplicationEventPublisher;
import uk.codery.jclaim.event.MatchEvent;
import uk.codery.jclaim.event.MatchEventSink;

import java.util.Objects;

/**
 * Bridges JClaim {@link MatchEvent stewardship events} to Spring's
 * {@link ApplicationEventPublisher} as {@link JclaimMatchEvent}. Applications
 * observe events via {@code @EventListener} methods.
 */
public final class SpringEventMatchSink implements MatchEventSink {

    private final ApplicationEventPublisher publisher;

    public SpringEventMatchSink(ApplicationEventPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public void accept(MatchEvent event) {
        publisher.publishEvent(new JclaimMatchEvent(this, event));
    }
}
