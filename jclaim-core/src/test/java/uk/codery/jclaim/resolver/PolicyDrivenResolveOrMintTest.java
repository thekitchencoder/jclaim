package uk.codery.jclaim.resolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.codery.jclaim.event.MatchAmbiguous;
import uk.codery.jclaim.event.MatchEvent;
import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.event.MatchUndecided;
import uk.codery.jclaim.id.HumanIdGenerator;
import uk.codery.jclaim.matching.MatchingPolicy;
import uk.codery.jclaim.matching.TriState;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the attribute-blocking + matching-policy branches of
 * {@link DefaultEntityResolver#resolveOrMint} reached when no exact alias owner
 * exists. Uses {@link InMemoryEntityStorage} with custom {@link MatchingPolicy}
 * stubs to drive each branch.
 */
class PolicyDrivenResolveOrMintTest {

    private static final SourceSystem ECOMMERCE = SourceSystem.of("ecommerce");
    private static final SourceSystem POS = SourceSystem.of("pos");
    private static final SourceSystem CRM = SourceSystem.of("crm");

    private InMemoryEntityStorage storage;
    private RecordingSink sink;
    private Supplier<UUID> uuidSupplier;

    @BeforeEach
    void setUp() {
        storage = new InMemoryEntityStorage();
        sink = new RecordingSink();
        // A single monotonic supplier shared across all mints in a test keeps
        // URNs unique (the counter feeds both the msb and lsb).
        uuidSupplier = deterministicUuids();
    }

    /** Seeds an entity directly into storage so it becomes a blocking candidate. */
    private Entity seed(SourceSystem source, String sourceId, String email) {
        Claim claim = new Claim(source, sourceId, List.of(
                MatchingAttribute.of("email", email)));
        DefaultEntityResolver seeding = resolverWith(MatchingPolicy.aliasOnly(), 100);
        return seeding.resolveOrMint(claim).entity();
    }

    private final Random humanIdRng = new Random(7);

    private DefaultEntityResolver resolverWith(MatchingPolicy policy, int maxCandidates) {
        return DefaultEntityResolver.builder(storage)
                .namespace("codery")
                .uuidSupplier(uuidSupplier)
                .humanIdGenerator(new HumanIdGenerator(humanIdRng))
                .clock(Clock.fixed(Instant.parse("2026-05-10T12:00:00Z"), ZoneOffset.UTC))
                .matchingPolicy(policy)
                .matchEventSink(sink)
                .maxCandidates(maxCandidates)
                .build();
    }

    @Test
    void singleMatchedCandidate_linksToWinner_noStewardshipEvent() {
        Entity existing = seed(ECOMMERCE, "cust-1", "alice@example.com");

        // New claim from a different source, same email -> blocks on email.
        Claim claim = new Claim(POS, "loyalty-9", List.of(
                MatchingAttribute.of("email", "alice@example.com")));
        MatchingPolicy policy = (c, cand) ->
                cand.id().equals(existing.id()) ? TriState.MATCHED : TriState.NOT_MATCHED;

        ResolutionResult result = resolverWith(policy, 100).resolveOrMint(claim);

        assertThat(result).isInstanceOf(ResolutionResult.Matched.class);
        assertThat(result.entity().id()).isEqualTo(existing.id());
        assertThat(result.entity().aliases()).contains(new Alias(POS, "loyalty-9"));
        // No MatchUndecided / MatchAmbiguous for a clean single match.
        assertThat(sink.events).isEmpty();
    }

    @Test
    void allUndetermined_mints_andEmitsSingleMatchUndecided() {
        seed(ECOMMERCE, "cust-1", "alice@example.com");
        seed(CRM, "crm-2", "alice@example.com");

        Claim claim = new Claim(POS, "loyalty-9", List.of(
                MatchingAttribute.of("email", "alice@example.com")));
        MatchingPolicy policy = (c, cand) -> TriState.UNDETERMINED;

        ResolutionResult result = resolverWith(policy, 100).resolveOrMint(claim);

        assertThat(result).isInstanceOf(ResolutionResult.Minted.class);
        assertThat(sink.events).hasSize(1);
        MatchUndecided event = (MatchUndecided) sink.events.get(0);
        assertThat(event.minted()).isEqualTo(result.entity());
        assertThat(event.candidates()).hasSize(2);
        assertThat(event.candidates())
                .allMatch(o -> o.policyResult() == TriState.UNDETERMINED);
        assertThat(event.candidatesConsidered()).isEqualTo(2);
        assertThat(event.candidatesFound()).isEqualTo(2);
        assertThat(event.candidatePoolTruncated()).isFalse();
    }

    @Test
    void multipleMatched_linksToOldest_andEmitsMatchAmbiguous() {
        // Two candidates with DISTINCT createdAt -> oldest wins.
        Entity older = seedAt(ECOMMERCE, "cust-1", "alice@example.com",
                Instant.parse("2026-01-01T00:00:00Z"));
        Entity newer = seedAt(CRM, "crm-2", "alice@example.com",
                Instant.parse("2026-02-01T00:00:00Z"));

        Claim claim = new Claim(POS, "loyalty-9", List.of(
                MatchingAttribute.of("email", "alice@example.com")));
        MatchingPolicy policy = (c, cand) -> TriState.MATCHED;

        ResolutionResult result = resolverWith(policy, 100).resolveOrMint(claim);

        assertThat(result).isInstanceOf(ResolutionResult.Matched.class);
        assertThat(result.entity().id()).isEqualTo(older.id());
        assertThat(result.entity().aliases()).contains(new Alias(POS, "loyalty-9"));

        assertThat(sink.events).hasSize(1);
        MatchAmbiguous event = (MatchAmbiguous) sink.events.get(0);
        assertThat(event.winner().id()).isEqualTo(older.id());
        assertThat(event.otherMatched()).hasSize(1);
        assertThat(event.otherMatched().get(0).id()).isEqualTo(newer.id());
        assertThat(event.candidates()).hasSize(2);
    }

    @Test
    void multipleMatched_equalCreatedAt_tiebreaksOnUrn() {
        // Fixed clock so both seeded entities share createdAt exactly; the
        // lexicographically-smaller URN must win the tiebreak.
        Instant fixed = Instant.parse("2026-03-03T03:03:03Z");
        Entity a = seedAt(ECOMMERCE, "cust-1", "alice@example.com", fixed);
        Entity b = seedAt(CRM, "crm-2", "alice@example.com", fixed);

        assertThat(a.createdAt()).isEqualTo(b.createdAt());
        Entity expectedWinner =
                a.id().urn().compareTo(b.id().urn()) <= 0 ? a : b;
        Entity expectedRunnerUp = expectedWinner.equals(a) ? b : a;

        Claim claim = new Claim(POS, "loyalty-9", List.of(
                MatchingAttribute.of("email", "alice@example.com")));
        MatchingPolicy policy = (c, cand) -> TriState.MATCHED;

        ResolutionResult result = resolverWith(policy, 100).resolveOrMint(claim);

        assertThat(result.entity().id()).isEqualTo(expectedWinner.id());
        MatchAmbiguous event = (MatchAmbiguous) sink.events.get(0);
        assertThat(event.otherMatched().get(0).id()).isEqualTo(expectedRunnerUp.id());
    }

    @Test
    void candidatePoolTruncated_flagsTruncationInEvent() {
        // Seed more overlapping entities than the cap; all UNDETERMINED so we
        // mint and capture a MatchUndecided carrying the truncation flag.
        for (int i = 0; i < 5; i++) {
            seed(SourceSystem.of("src-" + i), "id-" + i, "alice@example.com");
        }
        int cap = 3;
        Claim claim = new Claim(POS, "loyalty-9", List.of(
                MatchingAttribute.of("email", "alice@example.com")));
        MatchingPolicy policy = (c, cand) -> TriState.UNDETERMINED;

        ResolutionResult result = resolverWith(policy, cap).resolveOrMint(claim);

        assertThat(result).isInstanceOf(ResolutionResult.Minted.class);
        MatchUndecided event = (MatchUndecided) sink.events.get(0);
        assertThat(event.candidatePoolTruncated()).isTrue();
        assertThat(event.candidatesConsidered()).isEqualTo(cap);
        assertThat(event.candidatesFound()).isEqualTo(cap);
    }

    // --- helpers ---------------------------------------------------------

    /** Seeds an entity with a specific createdAt by minting via a fixed clock. */
    private Entity seedAt(SourceSystem source, String sourceId, String email,
                          Instant createdAt) {
        DefaultEntityResolver seeding = DefaultEntityResolver.builder(storage)
                .namespace("codery")
                .uuidSupplier(uuidSupplier)
                .humanIdGenerator(new HumanIdGenerator(humanIdRng))
                .clock(Clock.fixed(createdAt, ZoneOffset.UTC))
                .matchingPolicy(MatchingPolicy.aliasOnly())
                .build();
        return seeding.resolveOrMint(new Claim(source, sourceId, List.of(
                MatchingAttribute.of("email", email)))).entity();
    }

    private static Supplier<UUID> deterministicUuids() {
        long[] counter = {0L};
        return () -> {
            long ts = System.currentTimeMillis();
            long msb = (ts << 16) | 0x7000L | (counter[0] & 0x0FFFL);
            long lsb = 0x8000_0000_0000_0000L | (counter[0]++);
            return new UUID(msb, lsb);
        };
    }

    private static final class RecordingSink implements MatchEventSink {
        final List<MatchEvent> events = new ArrayList<>();

        @Override
        public void accept(MatchEvent event) {
            events.add(event);
        }
    }
}
