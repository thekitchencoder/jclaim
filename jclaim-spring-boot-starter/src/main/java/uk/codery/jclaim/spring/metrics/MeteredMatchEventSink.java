package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import uk.codery.jclaim.event.CandidatePoolTruncated;
import uk.codery.jclaim.event.MatchEvent;
import uk.codery.jclaim.event.MatchEventSink;

import java.util.Objects;

/**
 * Decorator that records the {@code jclaim.matching.pool_truncated_total} counter
 * before forwarding each {@link MatchEvent} to an upstream {@link MatchEventSink}.
 * The counter increments once per {@link CandidatePoolTruncated} event — i.e. once
 * for every {@code resolveOrMint} whose candidate pool hit the {@code maxCandidates}
 * cap, across all outcomes (mint, single match, ambiguous, or undecided).
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
        if (event instanceof CandidatePoolTruncated) {
            poolTruncated.increment();
        }
        delegate.accept(event);
    }
}
