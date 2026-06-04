package uk.codery.jclaim.event;

/**
 * Pluggable sink for {@link MatchEvent stewardship events}. The default
 * implementation is a no-op so callers can adopt the library without
 * configuring observability up front. Production deployments wire this to
 * SLF4J, Spring's {@code ApplicationEventPublisher}, a message broker, or any
 * other delivery mechanism.
 *
 * <p>Implementations must be thread-safe — the resolver may invoke
 * {@link #accept(MatchEvent)} from multiple threads. Implementations should not
 * throw; the resolver swallows and logs any {@code RuntimeException} a sink
 * raises.
 */
@FunctionalInterface
public interface MatchEventSink {

    /** Receives a stewardship event. Implementations must not throw. */
    void accept(MatchEvent event);

    /** A sink that discards every event. */
    static MatchEventSink noop() {
        return event -> {
        };
    }
}
