package uk.codery.jclaim.resolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.codery.jclaim.event.AttributeDiff;
import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.event.MatchEvent;
import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.id.HumanIdFormat;
import uk.codery.jclaim.id.HumanIdGenerator;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.storage.EntityStorage;
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
        assertThat(HumanIdFormat.DEFAULT.isValid(result.entity().humanId())).isTrue();
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
        // preferredName is claim-only — additive, not a conflict — so it is
        // intentionally absent from differingValues.
        assertThat(event.differingValues()).containsExactly(
                new AttributeDiff("email", "alice@example.com", "alice.new@example.com")
        );
    }

    @Test
    void resolveOrMint_claimAddsNewAttribute_doesNotEmitConflict() {
        Claim original = new Claim(ECOMMERCE, "cust-1", List.of(
                MatchingAttribute.of("email", "alice@example.com")));
        Claim withExtra = new Claim(ECOMMERCE, "cust-1", List.of(
                MatchingAttribute.of("email", "alice@example.com"),
                MatchingAttribute.of("preferredName", "Ali")));

        resolver.resolveOrMint(original);
        ResolutionResult result = resolver.resolveOrMint(withExtra);

        assertThat(result).isInstanceOf(ResolutionResult.Matched.class);
        // A claim that only adds a previously-unseen attribute is additive, not
        // a conflict — no stewardship event is emitted.
        assertThat(sink.events).isEmpty();
    }

    @Test
    void builder_rejectsNonPositiveMaxCandidates() {
        assertThatThrownBy(() ->
                DefaultEntityResolver.builder(storage).maxCandidates(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                DefaultEntityResolver.builder(storage).maxCandidates(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_defaultPolicyIsAliasOnly_soBehaviourIsUnchanged() {
        // With no policy configured the resolver uses aliasOnly(); a second
        // claim for the same alias short-circuits to Matched with no events.
        DefaultEntityResolver defaults = DefaultEntityResolver.builder(storage)
                .namespace("codery")
                .uuidSupplier(deterministicUuids())
                .humanIdGenerator(new HumanIdGenerator(new Random(7)))
                .matchEventSink(sink)
                .build();

        Claim claim = new Claim(ECOMMERCE, "cust-1", List.of(
                MatchingAttribute.of("email", "alice@example.com")));
        assertThat(defaults.resolveOrMint(claim))
                .isInstanceOf(ResolutionResult.Minted.class);
        assertThat(defaults.resolveOrMint(claim))
                .isInstanceOf(ResolutionResult.Matched.class);
        assertThat(sink.events).isEmpty();
    }

    @Test
    void mintsWithConfiguredEntityType() {
        EntityStorage storage = new InMemoryEntityStorage();
        EntityResolver resolver = DefaultEntityResolver.builder(storage)
                .namespace("acme")
                .entityType("customer")
                .build();
        ResolutionResult r = resolver.resolveOrMint(
                new Claim(SourceSystem.of("crm"), "u-1", List.of()));
        Entity e = ((ResolutionResult.Minted) r).entity();
        assertThat(e.id().urn()).startsWith("urn:acme:customer:");
        assertThat(e.id().type()).isEqualTo("customer");
    }

    @Test
    void mintsHumanIdWithConfiguredTemplate() {
        EntityStorage storage = new InMemoryEntityStorage();
        EntityResolver resolver = DefaultEntityResolver.builder(storage)
                .humanIdTemplate("JG??????")
                .build();
        ResolutionResult r = resolver.resolveOrMint(
                new Claim(SourceSystem.of("crm"), "u-2", List.of()));
        Entity e = ((ResolutionResult.Minted) r).entity();
        assertThat(e.humanId()).startsWith("JG").hasSize(8);
    }

    @Test
    void humanIdTemplateRejectsInvalidTemplateEagerly() {
        EntityStorage storage = new InMemoryEntityStorage();
        DefaultEntityResolver.Builder builder = DefaultEntityResolver.builder(storage);
        assertThatThrownBy(() -> builder.humanIdTemplate("AB")) // < 2 placeholders
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unconfiguredResolverMintsDefaultUrnAndNoHumanId() {
        EntityStorage storage = new InMemoryEntityStorage();
        EntityResolver resolver = DefaultEntityResolver.builder(storage).build();
        ResolutionResult r = resolver.resolveOrMint(
                new Claim(SourceSystem.of("crm"), "u-legacy", List.of()));
        Entity e = ((ResolutionResult.Minted) r).entity();
        assertThat(e.id().urn()).startsWith("urn:codery:entity:");
        assertThat(e.id().type()).isEqualTo("entity");
        assertThat(e.humanId()).isNull();
    }

    @Test
    void noTemplate_mintsEntityWithNullHumanId() {
        EntityStorage storage = new InMemoryEntityStorage();
        EntityResolver resolver = DefaultEntityResolver.builder(storage).build(); // no template
        Entity e = ((ResolutionResult.Minted) resolver.resolveOrMint(
                new Claim(SourceSystem.of("crm"), "u-1", List.of()))).entity();
        assertThat(e.humanId()).isNull();
    }

    @Test
    void nullTemplate_mintsNoHumanId() {
        EntityStorage storage = new InMemoryEntityStorage();
        EntityResolver resolver = DefaultEntityResolver.builder(storage)
                .humanIdTemplate(null).build();
        Entity e = ((ResolutionResult.Minted) resolver.resolveOrMint(
                new Claim(SourceSystem.of("crm"), "u-3", List.of()))).entity();
        assertThat(e.humanId()).isNull();
    }

    @Test
    void blankTemplate_mintsNoHumanId() {
        EntityStorage storage = new InMemoryEntityStorage();
        EntityResolver resolver = DefaultEntityResolver.builder(storage)
                .humanIdTemplate("  ").build();
        Entity e = ((ResolutionResult.Minted) resolver.resolveOrMint(
                new Claim(SourceSystem.of("crm"), "u-2", List.of()))).entity();
        assertThat(e.humanId()).isNull();
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
