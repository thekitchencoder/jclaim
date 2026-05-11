package uk.codery.jclaim.retail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.codery.jclaim.event.AttributeDiff;
import uk.codery.jclaim.event.ConflictEventSink;
import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.id.HumanIdGenerator;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end reconciliation tests against the retail synthetic dataset.
 *
 * <p>The resolver in this release matches only on the {@code (source,
 * sourceId)} alias, so an external "linking decision" is required to fold
 * a multi-source customer into one entity. The fixture loader supplies the
 * ground-truth grouping; the {@link #ingest(EntityResolver, List, Map)}
 * helper plays the role of that linking decision — for each customer's
 * first claim it calls {@code resolveOrMint}, for the rest it calls
 * {@code addAlias}. This is exactly the path a stronger matching policy
 * will exercise once JSpec composition lands.
 */
class RetailReconciliationTest {

    private RetailFixtures fixtures;
    private InMemoryEntityStorage storage;
    private RecordingConflictSink conflictSink;
    private EntityResolver resolver;

    @BeforeEach
    void setUp() {
        fixtures = RetailFixtures.load();
        storage = new InMemoryEntityStorage();
        conflictSink = new RecordingConflictSink();
        resolver = DefaultEntityResolver.builder(storage)
                .namespace("codery")
                .uuidSupplier(deterministicUuids())
                .humanIdGenerator(new HumanIdGenerator(new Random(42)))
                .clock(Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC))
                .conflictSink(conflictSink)
                .build();
    }

    @Test
    void singleSourceCustomer_resolveOrMint_mintsExactlyOneEntity() {
        Claim only = fixtures.claimsFor("cust-005").get(0);

        ResolutionResult result = resolver.resolveOrMint(only);

        assertThat(result).isInstanceOf(ResolutionResult.Minted.class);
        assertThat(storage.size()).isEqualTo(1);
        assertThat(result.entity().aliases()).containsExactly(only.asAlias());
        assertThat(result.entity().attributes()).containsExactlyElementsOf(only.attributes());
    }

    @Test
    void sameSourceIdResolvedTwice_secondCallMatchesWithoutDuplicating() {
        Claim ecom = sourceClaim("cust-001", "ecommerce");

        ResolutionResult first = resolver.resolveOrMint(ecom);
        ResolutionResult second = resolver.resolveOrMint(ecom);

        assertThat(first).isInstanceOf(ResolutionResult.Minted.class);
        assertThat(second).isInstanceOf(ResolutionResult.Matched.class);
        assertThat(second.entity()).isEqualTo(first.entity());
        assertThat(storage.size()).isEqualTo(1);
        assertThat(conflictSink.events).isEmpty();
    }

    @Test
    void multiSourceCustomer_addAliasLinksAllSourceRecordsToOneEntity() {
        List<Claim> jane = fixtures.claimsFor("cust-001");
        assertThat(jane).hasSize(4);

        Entity minted = resolver.resolveOrMint(jane.get(0)).entity();
        for (int i = 1; i < jane.size(); i++) {
            resolver.addAlias(minted.id(), jane.get(i).source(), jane.get(i).sourceId());
        }

        assertThat(storage.size()).isEqualTo(1);
        Entity refreshed = resolver.getByUrn(minted.id());
        assertThat(refreshed.aliases()).hasSize(4);
        for (Claim claim : jane) {
            assertThat(resolver.findByAlias(claim.source(), claim.sourceId()))
                    .as("alias %s must resolve back to the canonical entity", claim.asAlias())
                    .contains(refreshed);
        }
    }

    @Test
    void conflictEvent_emittedWhenMatchingClaimAssertsDivergedAttributes() {
        Claim baseline = sourceClaim("cust-010", "ecommerce");
        Claim mutated = fixtures.updateClaims().stream()
                .filter(c -> c.source().name().equals("ecommerce")
                        && c.sourceId().equals("ec-10010"))
                .findFirst()
                .orElseThrow();

        resolver.resolveOrMint(baseline);
        ResolutionResult result = resolver.resolveOrMint(mutated);

        assertThat(result).isInstanceOf(ResolutionResult.Matched.class);
        // Stored attributes are unchanged — no silent update.
        assertThat(result.entity().attributes())
                .containsExactlyElementsOf(baseline.attributes());

        assertThat(conflictSink.events).hasSize(1);
        EntityAttributesConflicted event = conflictSink.events.get(0);
        assertThat(event.stored()).isEqualTo(result.entity());
        assertThat(event.incoming()).isEqualTo(mutated);
        assertThat(event.differences()).contains(
                new AttributeDiff("phone", "+44 7700 900110", "+44 7700 900911"));
    }

    @Test
    void fullDataset_naturalOrder_reconcilesToGroundTruth() {
        List<Claim> claims = new ArrayList<>(fixtures.allClaims());
        ingest(resolver, claims, fixtures.claimsByCustomer());
        assertGraphMatchesGroundTruth();
    }

    @Test
    void fullDataset_reverseOrder_reconcilesToSameGroundTruth() {
        List<Claim> claims = new ArrayList<>(fixtures.allClaims());
        Collections.reverse(claims);
        ingest(resolver, claims, fixtures.claimsByCustomer());
        assertGraphMatchesGroundTruth();
    }

    @Test
    void fullDataset_shuffledOrder_reconcilesToSameGroundTruth() {
        List<Claim> claims = new ArrayList<>(fixtures.allClaims());
        // Deterministic shuffle — seed fixed so failures reproduce.
        Collections.shuffle(claims, new Random(2026_05_11L));
        ingest(resolver, claims, fixtures.claimsByCustomer());
        assertGraphMatchesGroundTruth();
    }

    @Test
    void idempotency_loadingTheDatasetTwiceProducesAnIdenticalEntityGraph() {
        ingest(resolver, fixtures.allClaims(), fixtures.claimsByCustomer());
        Map<String, EntityId> firstPass = snapshotCustomerToEntity();
        int firstPassSize = storage.size();

        ingest(resolver, fixtures.allClaims(), fixtures.claimsByCustomer());
        Map<String, EntityId> secondPass = snapshotCustomerToEntity();

        assertThat(storage.size()).isEqualTo(firstPassSize);
        assertThat(secondPass).isEqualTo(firstPass);
    }

    @Test
    void twoCustomersWithSameDisplayName_remainDistinctEntities() {
        ingest(resolver, fixtures.allClaims(), fixtures.claimsByCustomer());

        Entity smithA = resolver.findByAlias(SourceSystem.of("ecommerce"), "ec-10006").orElseThrow();
        Entity smithB = resolver.findByAlias(SourceSystem.of("crm"), "crm-30007").orElseThrow();

        assertThat(smithA.id()).isNotEqualTo(smithB.id());
        // Both customers carry "Smith" as last_name, but they map to different entities.
        assertThat(attribute(smithA, "last_name")).isEqualTo("Smith");
    }

    @Test
    void twoCustomersSharingAPhone_remainDistinctUnderExactAliasMatching() {
        ingest(resolver, fixtures.allClaims(), fixtures.claimsByCustomer());

        Entity sophia = resolver.findByAlias(SourceSystem.of("ecommerce"), "ec-10008").orElseThrow();
        Entity mateo = resolver.findByAlias(SourceSystem.of("ecommerce"), "ec-10009").orElseThrow();

        assertThat(sophia.id()).isNotEqualTo(mateo.id());
        assertThat(attribute(sophia, "phone")).isEqualTo("+44 7700 900108");
        assertThat(attribute(mateo, "phone")).isEqualTo("+44 7700 900108");
    }

    @Test
    void phoneFormatVariation_doesNotAutoMerge_butAddAliasLinksRecords() {
        // Without addAlias, two source records for cust-003 would mint two entities,
        // since exact-alias matching ignores attribute similarity.
        List<Claim> records = fixtures.claimsFor("cust-003");
        InMemoryEntityStorage isolated = new InMemoryEntityStorage();
        EntityResolver isolatedResolver = DefaultEntityResolver.builder(isolated)
                .uuidSupplier(deterministicUuids())
                .humanIdGenerator(new HumanIdGenerator(new Random(99)))
                .build();
        for (Claim claim : records) {
            isolatedResolver.resolveOrMint(claim);
        }
        assertThat(isolated.size()).isEqualTo(records.size());

        // With the ground-truth-aware ingest, both records land on one entity.
        ingest(resolver, records, Map.of("cust-003", records));
        assertThat(storage.size()).isEqualTo(1);
    }

    @Test
    void updateClaimsBatch_emitsConflictEventsAgainstStoredAttributes() {
        // Stage 1 — full baseline ingestion, no conflicts expected.
        ingest(resolver, fixtures.allClaims(), fixtures.claimsByCustomer());
        assertThat(conflictSink.events)
                .as("baseline ingest must not emit conflicts")
                .isEmpty();

        // Stage 2 — apply updates and assert each produces a Matched result
        // plus a conflict event. Stored attributes must remain untouched.
        Map<Alias, List<MatchingAttribute>> storedBefore = snapshotAttributesByAlias();
        for (Claim update : fixtures.updateClaims()) {
            ResolutionResult result = resolver.resolveOrMint(update);
            assertThat(result).isInstanceOf(ResolutionResult.Matched.class);
        }
        assertThat(conflictSink.events).hasSize(fixtures.updateClaims().size());
        assertThat(snapshotAttributesByAlias()).isEqualTo(storedBefore);
    }

    // ── Ingestion driver and assertion helpers ─────────────────────────

    /**
     * Plays the role of an external matching policy: for each claim, looks
     * up which ground-truth customer it belongs to, mints a new entity the
     * first time that customer is seen, and attaches subsequent claims as
     * aliases on the already-minted entity.
     */
    private static void ingest(
            EntityResolver resolver,
            List<Claim> orderedClaims,
            Map<String, List<Claim>> groundTruth) {
        Map<Alias, String> aliasToCustomer = new HashMap<>();
        for (Map.Entry<String, List<Claim>> e : groundTruth.entrySet()) {
            for (Claim c : e.getValue()) {
                aliasToCustomer.put(c.asAlias(), e.getKey());
            }
        }

        Map<String, EntityId> customerToEntity = new HashMap<>();
        for (Claim claim : orderedClaims) {
            String customerId = aliasToCustomer.get(claim.asAlias());
            EntityId existing = customerToEntity.get(customerId);
            if (existing != null) {
                resolver.addAlias(existing, claim.source(), claim.sourceId());
            } else {
                Entity minted = resolver.resolveOrMint(claim).entity();
                customerToEntity.put(customerId, minted.id());
            }
        }
    }

    private void assertGraphMatchesGroundTruth() {
        // One entity per ground-truth customer.
        assertThat(storage.size()).isEqualTo(fixtures.customerCount());

        // Every claim resolves back to its customer's canonical entity, and
        // each customer's claims all resolve to the same entity.
        for (Map.Entry<String, List<Claim>> customer : fixtures.claimsByCustomer().entrySet()) {
            Set<EntityId> entityIds = new HashSet<>();
            for (Claim claim : customer.getValue()) {
                Entity resolved = resolver.findByAlias(claim.source(), claim.sourceId())
                        .orElseThrow(() -> new AssertionError(
                                "Customer " + customer.getKey() + " has unresolved alias "
                                        + claim.asAlias()));
                entityIds.add(resolved.id());
            }
            assertThat(entityIds)
                    .as("all aliases for %s must point at one entity", customer.getKey())
                    .hasSize(1);
        }
    }

    private Map<String, EntityId> snapshotCustomerToEntity() {
        Map<String, EntityId> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<Claim>> entry : fixtures.claimsByCustomer().entrySet()) {
            Claim first = entry.getValue().get(0);
            Entity entity = resolver.findByAlias(first.source(), first.sourceId())
                    .orElseThrow(() -> new AssertionError(
                            "Customer " + entry.getKey() + " missing after ingest"));
            snapshot.put(entry.getKey(), entity.id());
        }
        return snapshot;
    }

    private Map<Alias, List<MatchingAttribute>> snapshotAttributesByAlias() {
        Map<Alias, List<MatchingAttribute>> snapshot = new LinkedHashMap<>();
        for (Claim claim : fixtures.allClaims()) {
            Entity entity = resolver.findByAlias(claim.source(), claim.sourceId()).orElseThrow();
            snapshot.put(claim.asAlias(), entity.attributes());
        }
        return snapshot;
    }

    private Claim sourceClaim(String customerId, String sourceName) {
        return fixtures.claimsFor(customerId).stream()
                .filter(c -> c.source().name().equals(sourceName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Customer " + customerId + " has no " + sourceName + " record"));
    }

    private static Object attribute(Entity entity, String name) {
        return entity.attributes().stream()
                .filter(a -> a.name().equals(name))
                .map(MatchingAttribute::value)
                .findFirst()
                .orElse(null);
    }

    /** Test-scope UUID v7-shaped supplier so URN regex passes deterministically. */
    private static Supplier<UUID> deterministicUuids() {
        long[] counter = {0L};
        return () -> {
            long ts = System.currentTimeMillis();
            long msb = (ts << 16) | 0x7000L | (counter[0] & 0x0FFFL);
            long lsb = 0x8000_0000_0000_0000L | (counter[0]++);
            return new UUID(msb, lsb);
        };
    }

    private static final class RecordingConflictSink implements ConflictEventSink {
        final List<EntityAttributesConflicted> events = new ArrayList<>();

        @Override
        public void accept(EntityAttributesConflicted event) {
            events.add(event);
        }
    }
}
