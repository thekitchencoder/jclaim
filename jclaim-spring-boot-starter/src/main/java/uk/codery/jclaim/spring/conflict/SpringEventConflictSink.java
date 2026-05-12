package uk.codery.jclaim.spring.conflict;

import org.springframework.context.ApplicationEventPublisher;
import uk.codery.jclaim.event.ConflictEventSink;
import uk.codery.jclaim.event.EntityAttributesConflicted;

import java.util.Objects;

/**
 * Bridges {@link EntityAttributesConflicted} events to Spring's
 * {@link ApplicationEventPublisher} as {@link JclaimConflictEvent}.
 * Applications observe conflicts via {@code @EventListener} methods.
 */
public final class SpringEventConflictSink implements ConflictEventSink {

    private final ApplicationEventPublisher publisher;

    public SpringEventConflictSink(ApplicationEventPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public void accept(EntityAttributesConflicted event) {
        publisher.publishEvent(new JclaimConflictEvent(this, event));
    }
}
