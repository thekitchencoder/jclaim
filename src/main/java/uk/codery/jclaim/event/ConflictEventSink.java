package uk.codery.jclaim.event;

/**
 * Pluggable sink for {@link EntityAttributesConflicted} events. The default
 * implementation is a no-op so callers can adopt the library without
 * configuring observability up front. Production deployments wire this to
 * SLF4J, Spring's {@code ApplicationEventPublisher}, a message broker, or
 * any other delivery mechanism.
 *
 * <p>Implementations must be thread-safe — the resolver may invoke
 * {@link #accept(EntityAttributesConflicted)} from multiple threads.
 */
@FunctionalInterface
public interface ConflictEventSink {

    /** Receives a conflict event. Implementations must not throw. */
    void accept(EntityAttributesConflicted event);

    /** A sink that discards every event. */
    static ConflictEventSink noop() {
        return event -> {
        };
    }
}
