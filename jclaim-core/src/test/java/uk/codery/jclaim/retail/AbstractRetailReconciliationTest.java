package uk.codery.jclaim.retail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.codery.jclaim.event.AttributeDiff;
import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.fixtures.DeterministicUuids;
import uk.codery.jclaim.fixtures.GroundTruthIngester;
import uk.codery.jclaim.fixtures.RecordingConflictSink;
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
import uk.codery.jclaim.storage.EntityStorage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end reconciliation contract against the retail synthetic dataset,
 * parameterised by storage factory. Each concrete subclass provides a fresh
 * {@link EntityStorage} per test via {@link #newStorage()} — the in-memory
 * adapter in {@code jclaim-core}, Postgres and Mongo in their respective
 * adapter modules. All three must produce identical entity graphs.
 *
 * <p>The resolver in this release matches only on {@code (source, sourceId)},
 * so an external linking decision is required to fold a multi-source customer
 * into one entity. {@link GroundTruthIngester} plays that role using the
 * fixture's ground-truth grouping — the same path the future JSpec-driven
 * matching policy will exercise once it lands.
 */
public abstract class AbstractRetailReconciliationTest {

    protected RetailFixtures fixtures;
    protected EntityStorage storage;
    protected RecordingConflictSink conflictSink;
    protected EntityResolver resolver;

    /** Returns a fresh, empty {@link EntityStorage} for the test about to run. */
    protected abstract EntityStorage newStorage();

    @BeforeEach
    final void setUp() {
        fixtures = RetailFixtures.load();
        storage = newStorage();
        conflictSink = new RecordingConflictSink();
        resolver = DefaultEntityResolver.builder(storage)
                .namespace("codery")
                .uuidSupplier(DeterministicUuids.supplier())
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
        assertThat(countEntitiesReachableVia(List.of(only))).isEqualTo(1);
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
        assertThat(countEntitiesReachableVia(List.of(ecom))).isEqualTo(1);
        assertThat(conflictSink.events()).isEmpty();
    }

    @Test
    void multiSourceCustomer_addAliasLinksAllSourceRecordsToOneEntity() {
        List<Claim> jane = fixtures.claimsFor("cust-001");
        assertThat(jane).hasSize(4);

        Entity minted = resolver.resolveOrMint(jane.get(0)).entity();
        for (int i = 1; i < jane.size(); i++) {
            resolver.addAlias(minted.id(), jane.get(i).source(), jane.get(i).sourceId());
        }

        assertThat(countEntitiesReachableVia(jane)).isEqualTo(1);
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

        assertThat(conflictSink.events()).hasSize(1);
        EntityAttributesConflicted event = conflictSink.events().get(0);
        assertThat(event.stored()).isEqualTo(result.entity());
        assertThat(event.incoming()).isEqualTo(mutated);
        assertThat(event.differences()).contains(
                new AttributeDiff("phone", "+44 7700 900110", "+44 7700 900911"));
    }

    @Test
    void fullDataset_naturalOrder_reconcilesToGroundTruth() {
        List<Claim> claims = new ArrayList<>(fixtures.allClaims());
        GroundTruthIngester.ingest(resolver, claims, fixtures.claimsByCustomer());
        assertGraphMatchesGroundTruth();
    }

    @Test
    void fullDataset_reverseOrder_reconcilesToSameGroundTruth() {
        List<Claim> claims = new ArrayList<>(fixtures.allClaims());
        Collections.reverse(claims);
        GroundTruthIngester.ingest(resolver, claims, fixtures.claimsByCustomer());
        assertGraphMatchesGroundTruth();
    }

    @Test
    void fullDataset_shuffledOrder_reconcilesToSameGroundTruth() {
        List<Claim> claims = new ArrayList<>(fixtures.allClaims());
        // Deterministic shuffle — seed fixed so failures reproduce.
        Collections.shuffle(claims, new Random(2026_05_11L));
        GroundTruthIngester.ingest(resolver, claims, fixtures.claimsByCustomer());
        assertGraphMatchesGroundTruth();
    }

    @Test
    void idempotency_loadingTheDatasetTwiceProducesAnIdenticalEntityGraph() {
        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByCustomer());
        Map<String, EntityId> firstPass = snapshotCustomerToEntity();
        int firstPassSize = countEntitiesReachableVia(fixtures.allClaims());

        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByCustomer());
        Map<String, EntityId> secondPass = snapshotCustomerToEntity();

        assertThat(countEntitiesReachableVia(fixtures.allClaims())).isEqualTo(firstPassSize);
        assertThat(secondPass).isEqualTo(firstPass);
    }

    @Test
    void twoCustomersWithSameDisplayName_remainDistinctEntities() {
        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByCustomer());

        Entity smithA = resolver.findByAlias(SourceSystem.of("ecommerce"), "ec-10006").orElseThrow();
        Entity smithB = resolver.findByAlias(SourceSystem.of("crm"), "crm-30007").orElseThrow();

        assertThat(smithA.id()).isNotEqualTo(smithB.id());
        // Both customers carry "Smith" as last_name, but they map to different entities.
        assertThat(attribute(smithA, "last_name")).isEqualTo("Smith");
    }

    @Test
    void twoCustomersSharingAPhone_remainDistinctUnderExactAliasMatching() {
        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByCustomer());

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
        EntityStorage isolated = newStorage();
        EntityResolver isolatedResolver = DefaultEntityResolver.builder(isolated)
                .uuidSupplier(DeterministicUuids.supplier())
                .humanIdGenerator(new HumanIdGenerator(new Random(99)))
                .build();
        for (Claim claim : records) {
            isolatedResolver.resolveOrMint(claim);
        }
        assertThat(countEntitiesReachableVia(isolated, records)).isEqualTo(records.size());

        // With the ground-truth-aware ingest, both records land on one entity.
        GroundTruthIngester.ingest(resolver, records, Map.of("cust-003", records));
        assertThat(countEntitiesReachableVia(records)).isEqualTo(1);
    }

    @Test
    void updateClaimsBatch_emitsConflictEventsAgainstStoredAttributes() {
        // Stage 1 — full baseline ingestion, no conflicts expected.
        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByCustomer());
        assertThat(conflictSink.events())
                .as("baseline ingest must not emit conflicts")
                .isEmpty();

        // Stage 2 — apply updates and assert each produces a Matched result
        // plus a conflict event. Stored attributes must remain untouched.
        Map<Alias, List<MatchingAttribute>> storedBefore = snapshotAttributesByAlias();
        for (Claim update : fixtures.updateClaims()) {
            ResolutionResult result = resolver.resolveOrMint(update);
            assertThat(result).isInstanceOf(ResolutionResult.Matched.class);
        }
        assertThat(conflictSink.events()).hasSize(fixtures.updateClaims().size());
        assertThat(snapshotAttributesByAlias()).isEqualTo(storedBefore);
    }

    // ── Assertion helpers ───────────────────────────────────────────────

    private void assertGraphMatchesGroundTruth() {
        // One entity per ground-truth customer, derived through the port.
        assertThat(countEntitiesReachableVia(fixtures.allClaims()))
                .isEqualTo(fixtures.customerCount());

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

    private int countEntitiesReachableVia(Collection<Claim> claims) {
        return countEntitiesReachableVia(storage, claims);
    }

    private static int countEntitiesReachableVia(EntityStorage store, Collection<Claim> claims) {
        Set<EntityId> ids = new HashSet<>();
        for (Claim claim : claims) {
            store.findByAlias(claim.asAlias()).ifPresent(e -> ids.add(e.id()));
        }
        return ids.size();
    }

    private static Object attribute(Entity entity, String name) {
        return entity.attributes().stream()
                .filter(a -> a.name().equals(name))
                .map(MatchingAttribute::value)
                .findFirst()
                .orElse(null);
    }
}
