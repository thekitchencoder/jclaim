package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.codery.jclaim.event.AttributeDiff;
import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.event.MatchAmbiguous;
import uk.codery.jclaim.event.MatchEvent;
import uk.codery.jclaim.event.MatchUndecided;
import uk.codery.jclaim.matching.MatchingPolicy;
import uk.codery.jclaim.matching.TriState;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;
import uk.codery.jclaim.storage.EntityStorage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 16 — the {@code jclaim.matching.pool_truncated_total} counter increments
 * when a fired stewardship event reports a truncated candidate pool.
 */
class MeteredMatchEventSinkTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JclaimAutoConfiguration.class,
                    JclaimMetricsAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            // Cap the pool at 1 so two attribute-sharing candidates force truncation.
            .withPropertyValues("jclaim.matching.max-candidates=1")
            .withUserConfiguration(UndeterminedPolicyConfig.class);

    @Test
    void incrementsCounterWhenTruncatedStewardshipEventFires() {
        runner.run(ctx -> {
            EntityStorage storage = ctx.getBean(EntityStorage.class);
            EntityResolver resolver = ctx.getBean(EntityResolver.class);
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);

            MatchingAttribute email = MatchingAttribute.of("email", "shared@example.com");
            // Seed two distinct entities sharing the blocking attribute, bypassing the
            // resolver so the seeding itself fires no events.
            seed(storage, SourceSystem.of("crm"), "seed-1", email);
            seed(storage, SourceSystem.of("pos"), "seed-2", email);

            // New alias, same attribute: findCandidates(limit=1) returns 1 of 2 →
            // truncated. UNDETERMINED policy → MatchUndecided(candidatePoolTruncated=true).
            resolver.resolveOrMint(new Claim(SourceSystem.of("erp"), "probe", List.of(email)));

            assertThat(registry.counter("jclaim.matching.pool_truncated_total").count())
                    .isEqualTo(1.0);
        });
    }

    // -- Direct unit coverage of every switch arm + both truncation flags -----

    @Test
    void directSinkCoversAllEventArmsAndTruncationFlags() {
        MeterRegistry registry = new SimpleMeterRegistry();
        var forwarded = new java.util.concurrent.atomic.AtomicReference<MatchEvent>();
        MeteredMatchEventSink sink = new MeteredMatchEventSink(forwarded::set, registry);

        Entity e = entity();
        Claim claim = new Claim(SourceSystem.of("crm"), "u-1", List.of());

        // MatchUndecided, NOT truncated -> counter stays at 0, still forwarded.
        sink.accept(new MatchUndecided(claim, e, List.of(), 0, 0, false));
        // MatchAmbiguous, truncated -> counter increments.
        sink.accept(new MatchAmbiguous(claim, e, List.of(entity()), List.of(), 2, 2, true));
        // EntityAttributesConflicted -> default arm, never counted.
        MatchEvent conflicted = new EntityAttributesConflicted(
                e, claim, List.of(new AttributeDiff("email", "a@x", "b@x")));
        sink.accept(conflicted);

        assertThat(registry.counter("jclaim.matching.pool_truncated_total").count())
                .isEqualTo(1.0);
        // The last event was forwarded to the delegate.
        assertThat(forwarded.get()).isSameAs(conflicted);
    }

    @Test
    void rejectsNullDelegate() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new MeteredMatchEventSink(null, new SimpleMeterRegistry()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("delegate");
    }

    private static Entity entity() {
        Instant now = Instant.now();
        return new Entity(EntityId.of("test", UUID.randomUUID()),
                null, List.of(), List.of(), null, now, now);
    }

    private static void seed(EntityStorage storage, SourceSystem source, String sourceId,
                             MatchingAttribute attr) {
        Alias alias = new Alias(source, sourceId);
        storage.resolveOrCreate(alias, () -> {
            Instant now = Instant.now();
            return new Entity(
                    EntityId.of("test", UUID.randomUUID()),
                    "HID-" + sourceId,
                    List.of(alias),
                    List.of(attr),
                    null,
                    now,
                    now);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class UndeterminedPolicyConfig {
        @Bean
        MatchingPolicy alwaysUndetermined() {
            return (Claim claim, Entity candidate) -> TriState.UNDETERMINED;
        }
    }
}
