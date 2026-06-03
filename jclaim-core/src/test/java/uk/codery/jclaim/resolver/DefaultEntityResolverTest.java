package uk.codery.jclaim.resolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.codery.jclaim.event.AttributeDiff;
import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.event.MatchEvent;
import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.id.HumanIdGenerator;
import uk.codery.jclaim.model.Claim;
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
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultEntityResolverTest {

    private static final SourceSystem ECOMMERCE = SourceSystem.of("ecommerce");
    private static final SourceSystem POS = SourceSystem.of("pos");

    private InMemoryEntityStorage storage;
    private RecordingSink sink;
    private DefaultEntityResolver resolver;

    @BeforeEach
    void setUp() {
        storage = new InMemoryEntityStorage();
        sink = new RecordingSink();
        resolver = DefaultEntityResolver.builder(storage)
                .namespace("codery")
                .uuidSupplier(deterministicUuids())
                .humanIdGenerator(new HumanIdGenerator(new Random(7)))
                .clock(Clock.fixed(Instant.parse("2026-05-10T12:00:00Z"), ZoneOffset.UTC))
                .matchEventSink(sink)
                .build();
    }

    @Test
    void resolveOrMint_firstClaim_mintsNewEntityCarryingAlias() {
        Claim claim = new Claim(ECOMMERCE, "cust-1", List.of(
                MatchingAttribute.of("email", "alice@example.com")));

        ResolutionResult result = resolver.resolveOrMint(claim);

        assertThat(result).isInstanceOf(ResolutionResult.Minted.class);
        assertThat(result.entity().id().urn()).startsWith("urn:codery:entity:");
        assertThat(result.entity().aliases()).containsExactly(claim.asAlias());
        assertThat(result.entity().attributes()).containsExactly(
                MatchingAttribute.of("email", "alice@example.com"));
        assertThat(HumanIdGenerator.isValid(result.entity().humanId())).isTrue();
        assertThat(sink.events).isEmpty();
    }

    @Test
    void resolveOrMint_sameClaimTwice_matchesOnSecondCall() {
        Claim claim = new Claim(ECOMMERCE, "cust-1", List.of(
                MatchingAttribute.of("email", "alice@example.com")));

        ResolutionResult first = resolver.resolveOrMint(claim);
        ResolutionResult second = resolver.resolveOrMint(claim);

        assertThat(first).isInstanceOf(ResolutionResult.Minted.class);
        assertThat(second).isInstanceOf(ResolutionResult.Matched.class);
        assertThat(second.entity()).isEqualTo(first.entity());
        assertThat(sink.events).isEmpty();
    }

    @Test
    void resolveOrMint_matchingClaimWithDivergedAttributes_emitsConflictEvent() {
        Claim original = new Claim(ECOMMERCE, "cust-1", List.of(
                MatchingAttribute.of("email", "alice@example.com"),
                MatchingAttribute.of("phone", "+44 1234 567890")));
        Claim updated = new Claim(ECOMMERCE, "cust-1", List.of(
                MatchingAttribute.of("email", "alice.new@example.com"),
                MatchingAttribute.of("phone", "+44 1234 567890"),
                MatchingAttribute.of("preferredName", "Ali")));

        resolver.resolveOrMint(original);
        ResolutionResult result = resolver.resolveOrMint(updated);

        assertThat(result).isInstanceOf(ResolutionResult.Matched.class);
        // Stored attributes must remain unchanged — no silent update.
        assertThat(result.entity().attributes()).containsExactly(
                MatchingAttribute.of("email", "alice@example.com"),
                MatchingAttribute.of("phone", "+44 1234 567890"));

        assertThat(sink.events).hasSize(1);
        EntityAttributesConflicted event = (EntityAttributesConflicted) sink.events.get(0);
        assertThat(event.stored()).isEqualTo(result.entity());
        assertThat(event.claim()).isEqualTo(updated);
        assertThat(event.differingValues()).containsExactlyInAnyOrder(
                new AttributeDiff("email", "alice@example.com", "alice.new@example.com"),
                new AttributeDiff("preferredName", null, "Ali")
        );
    }

    @Test
    void findByAlias_returnsEmptyWhenAliasUnknown() {
        assertThat(resolver.findByAlias(POS, "ghost")).isEmpty();
    }

    @Test
    void findByAlias_returnsEntityAfterResolveOrMint() {
        Claim claim = new Claim(ECOMMERCE, "cust-7", List.of());
        var minted = resolver.resolveOrMint(claim).entity();

        assertThat(resolver.findByAlias(ECOMMERCE, "cust-7")).contains(minted);
    }

    @Test
    void findByHumanId_returnsEntityAfterResolveOrMint() {
        Claim claim = new Claim(ECOMMERCE, "cust-9", List.of());
        var minted = resolver.resolveOrMint(claim).entity();

        assertThat(resolver.findByHumanId(minted.humanId())).contains(minted);
    }

    @Test
    void getByUrn_throwsForUnknownEntity() {
        EntityId nowhere = EntityId.of(UUID.randomUUID());
        assertThatThrownBy(() -> resolver.getByUrn(nowhere))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void addAlias_attachesAdditionalSourceIdentifier() {
        var minted = resolver.resolveOrMint(
                new Claim(ECOMMERCE, "cust-1", List.of())).entity();

        var updated = resolver.addAlias(minted.id(), POS, "loyalty-42");

        assertThat(updated.aliases()).hasSize(2);
        assertThat(resolver.findByAlias(POS, "loyalty-42")).contains(updated);
    }

    private static java.util.function.Supplier<UUID> deterministicUuids() {
        // Use UUID v7-shaped UUIDs derived from a counter so the URN regex
        // passes. Real production code uses UuidV7.supplier().
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
