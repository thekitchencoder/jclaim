package uk.codery.jclaim.id;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilteringPublicIdGeneratorTest {

    @Test
    void allowAll_returnsDelegateOutput_withoutReroll() {
        AtomicInteger calls = new AtomicInteger();
        PublicIdGenerator delegate = () -> {
            calls.incrementAndGet();
            return "ABC";
        };
        PublicIdGenerator gen =
                new FilteringPublicIdGenerator(delegate, FilteringPublicIdGenerator.ALLOW_ALL);
        assertThat(gen.generate()).isEqualTo("ABC");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void rejectingPredicate_rerollsUntilAccepted() {
        AtomicInteger n = new AtomicInteger();
        PublicIdGenerator delegate = () -> "C" + n.getAndIncrement(); // C0, C1, C2, ...
        Predicate<String> acceptThird = s -> s.equals("C2");
        PublicIdGenerator gen = new FilteringPublicIdGenerator(delegate, acceptThird, 10);
        assertThat(gen.generate()).isEqualTo("C2");
        assertThat(n.get()).isEqualTo(3);
    }

    @Test
    void budgetExhaustion_throws() {
        PublicIdGenerator delegate = () -> "X";
        Predicate<String> never = s -> false;
        PublicIdGenerator gen = new FilteringPublicIdGenerator(delegate, never, 3);
        assertThatThrownBy(gen::generate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3");
    }

    @Test
    void nullArgs_throwNpe() {
        assertThatNullPointerException().isThrownBy(
                () -> new FilteringPublicIdGenerator(null, FilteringPublicIdGenerator.ALLOW_ALL));
        assertThatNullPointerException().isThrownBy(
                () -> new FilteringPublicIdGenerator(() -> "X", null));
    }

    @Test
    void nonPositiveMaxAttempts_throwsIllegalArgument() {
        assertThatThrownBy(() -> new FilteringPublicIdGenerator(() -> "X", FilteringPublicIdGenerator.ALLOW_ALL, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FilteringPublicIdGenerator(() -> "X", FilteringPublicIdGenerator.ALLOW_ALL, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAllCandidates_trueForAllowAllSingleton() {
        PublicIdGenerator delegate = () -> "ABC";
        FilteringPublicIdGenerator gen =
                new FilteringPublicIdGenerator(delegate, FilteringPublicIdGenerator.ALLOW_ALL);
        assertThat(gen.acceptsAllCandidates()).isTrue();
    }

    @Test
    void acceptsAllCandidates_falseForRealPredicate() {
        PublicIdGenerator delegate = () -> "ABC";
        Predicate<String> denyB = s -> !s.contains("B");
        FilteringPublicIdGenerator gen = new FilteringPublicIdGenerator(delegate, denyB);
        assertThat(gen.acceptsAllCandidates()).isFalse();
    }

    @Test
    void acceptsAllCandidates_falseForEquivalentButDistinctAllowAllLambda() {
        // A user-supplied s -> true is a deliberate predicate, not the default
        // posture, so it is intentionally NOT treated as "accepts all".
        PublicIdGenerator delegate = () -> "ABC";
        FilteringPublicIdGenerator gen = new FilteringPublicIdGenerator(delegate, s -> true);
        assertThat(gen.acceptsAllCandidates()).isFalse();
    }
}
