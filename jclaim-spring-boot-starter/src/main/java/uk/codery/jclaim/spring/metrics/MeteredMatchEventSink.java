package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import uk.codery.jclaim.event.MatchAmbiguous;
import uk.codery.jclaim.event.MatchEvent;
import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.event.MatchUndecided;

import java.util.Objects;

/**
 * Decorator that records the {@code jclaim.matching.pool_truncated_total} counter
 * before forwarding each {@link MatchEvent} to an upstream {@link MatchEventSink}.
 * The counter increments whenever a fired stewardship event reports that the
 * candidate pool hit the resolver's {@code maxCandidates} cap.
 *
 * <p>Only {@link MatchUndecided} and {@link MatchAmbiguous} carry a truncation
 * flag — they are the non-positive / ambiguous outcomes the resolver surfaces.
 * Truncation that coincides with a clean single {@code Matched} outcome is
 * intentionally <em>not</em> counted: by design the resolver emits no event in
 * that case, so there is nothing here to observe.
 */
public final class MeteredMatchEventSink implements MatchEventSink {

    private final MatchEventSink delegate;
    private final Counter poolTruncated;

    public MeteredMatchEventSink(MatchEventSink delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(registry, "registry");
        this.poolTruncated = Counter.builder("jclaim.matching.pool_truncated_total")
                .description("Stewardship events fired with a truncated candidate pool")
                .register(registry);
    }

    @Override
    public void accept(MatchEvent event) {
        boolean truncated = switch (event) {
            case MatchUndecided u -> u.candidatePoolTruncated();
            case MatchAmbiguous a -> a.candidatePoolTruncated();
            default -> false;
        };
        if (truncated) {
            poolTruncated.increment();
        }
        delegate.accept(event);
    }
}
