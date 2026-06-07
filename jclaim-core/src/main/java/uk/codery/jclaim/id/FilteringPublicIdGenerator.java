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
        this.maxAttempts = maxAttempts;
    }

    @Override
    public String generate() {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String candidate = delegate.generate();
            if (acceptable.test(candidate)) {
                return candidate;
            }
            log.warn("Public ID candidate rejected by acceptance policy on attempt {}; re-rolling",
                    attempt + 1);
        }
        throw new IllegalStateException(
                "No acceptable public ID after " + maxAttempts + " attempts");
    }
}
