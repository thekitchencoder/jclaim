package uk.codery.jclaim.id;

import java.util.Objects;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link PublicIdGenerator} decorator that re-rolls its delegate until a
 * candidate satisfies an acceptance {@link Predicate}, within a bounded attempt
 * budget. Because it is itself a {@code PublicIdGenerator}, it composes over any
 * generation policy. With the {@link #ALLOW_ALL} predicate it never re-rolls and
 * is a transparent no-op.
 *
 * <p>This acceptance budget is independent of the resolver's uniqueness re-roll:
 * different failure modes with different odds, so they must not share a counter.
 */
public final class FilteringPublicIdGenerator implements PublicIdGenerator {

    /** Accepts every candidate; makes the decorator a transparent no-op. */
    public static final Predicate<String> ALLOW_ALL = s -> true;

    /** Default acceptance attempt budget. */
    public static final int DEFAULT_MAX_ATTEMPTS = 100;

    private static final Logger log = LoggerFactory.getLogger(FilteringPublicIdGenerator.class);

    private final PublicIdGenerator delegate;
    private final Predicate<String> acceptable;
    private final int maxAttempts;

    /** Decorator using {@link #DEFAULT_MAX_ATTEMPTS}. */
    public FilteringPublicIdGenerator(PublicIdGenerator delegate, Predicate<String> acceptable) {
        this(delegate, acceptable, DEFAULT_MAX_ATTEMPTS);
    }

    /** Decorator with an explicit acceptance attempt budget. */
    public FilteringPublicIdGenerator(PublicIdGenerator delegate,
                                      Predicate<String> acceptable,
                                      int maxAttempts) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.acceptable = Objects.requireNonNull(acceptable, "acceptable");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive, was " + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * Returns the first candidate the acceptance predicate accepts, re-rolling
     * the delegate up to the configured budget.
     *
     * @throws IllegalStateException if no candidate is accepted within
     *         {@code maxAttempts}. This is fail-fast: the resolver calls this
     *         without a {@code try/catch} and does not retry, so a misconfigured
     *         acceptance predicate surfaces to the caller rather than silently
     *         degrading.
     */
    @Override
    public String generate() {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String candidate = delegate.generate();
            if (acceptable.test(candidate)) {
                return candidate;
            }
            // DEBUG, not WARN: with a real acceptance predicate the odd rejection
            // is routine; only giving up entirely warrants an operator's attention.
            log.debug("Public ID candidate rejected by acceptance policy on attempt {}; re-rolling",
                    attempt + 1);
        }
        log.warn("Acceptance policy rejected all {} public ID candidates; giving up", maxAttempts);
        throw new IllegalStateException(
                "No acceptable public ID after " + maxAttempts + " attempts");
    }

    /**
     * Whether this decorator's acceptance predicate is the {@link #ALLOW_ALL}
     * singleton — i.e. it filters nothing and never re-rolls. The resolver uses
     * this to detect an unfiltered publicId posture and emit a discoverability
     * warning at build time (ADR-0003). A user-supplied predicate that happens to
     * accept everything is deliberately reported as {@code false}: it is an
     * explicit choice, not the default posture.
     */
    public boolean acceptsAllCandidates() {
        return acceptable == ALLOW_ALL;
    }
}
