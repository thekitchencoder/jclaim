package uk.codery.jclaim.spring.conflict;

import org.springframework.context.ApplicationEvent;
import uk.codery.jclaim.event.EntityAttributesConflicted;

import java.util.Objects;

/**
 * Spring {@link ApplicationEvent} carrying a JClaim
 * {@link EntityAttributesConflicted} payload. Published by the starter's
 * default {@link uk.codery.jclaim.event.ConflictEventSink} so application
 * code can react via {@code @EventListener} methods.
 */
public final class JclaimConflictEvent extends ApplicationEvent {

    private final EntityAttributesConflicted payload;

    public JclaimConflictEvent(Object source, EntityAttributesConflicted payload) {
        super(source);
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public EntityAttributesConflicted payload() {
        return payload;
    }
}
