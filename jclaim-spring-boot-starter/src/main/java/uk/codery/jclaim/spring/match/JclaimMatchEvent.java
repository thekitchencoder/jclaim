package uk.codery.jclaim.spring.match;

import org.springframework.context.ApplicationEvent;
import uk.codery.jclaim.event.MatchEvent;

import java.util.Objects;

/**
 * Spring {@link ApplicationEvent} carrying a JClaim {@link MatchEvent}
 * payload. Published by the starter's default {@link SpringEventMatchSink} so
 * application code can react via {@code @EventListener} methods. The payload is
 * the sealed {@link MatchEvent}; listeners pattern-match it to the concrete
 * variant they care about (for example {@code EntityAttributesConflicted}).
 */
public final class JclaimMatchEvent extends ApplicationEvent {

    private final MatchEvent payload;

    public JclaimMatchEvent(Object source, MatchEvent payload) {
        super(source);
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public MatchEvent payload() {
        return payload;
    }
}
