package uk.codery.jclaim.product;

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
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end reconciliation tests against the synthetic product dataset.
 *
 * <p>The resolver in this release matches only on the {@code (source,
 * sourceId)} alias, so an external "linking decision" is required to fold
 * a multi-source product (e.g. one with a shared GTIN across PIM,
 * warehouse, and marketplace) into one entity. The fixture loader supplies
 * the ground-truth grouping; {@link GroundTruthIngester} plays the role of
 * that linking decision.
 */
class ProductReconciliationTest {

    private ProductFixtures fixtures;
    private InMemoryEntityStorage storage;
    private RecordingConflictSink conflictSink;
    private EntityResolver resolver;

    @BeforeEach
    void setUp() {
        fixtures = ProductFixtures.load();
        storage = new InMemoryEntityStorage();
        conflictSink = new RecordingConflictSink();
        resolver = DefaultEntityResolver.builder(storage)
                .namespace("codery")
                .uuidSupplier(DeterministicUuids.supplier())
                .humanIdGenerator(new HumanIdGenerator(new Random(43)))
                .clock(Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC))
                .conflictSink(conflictSink)
                .build();
    }

    @Test
    void skuOnlyProduct_resolveOrMint_mintsExactlyOneEntity() {
        // prod-004 — warehouse-only bulk hardware, no GTIN.
        Claim only = fixtures.claimsFor("prod-004").get(0);

        ResolutionResult result = resolver.resolveOrMint(only);

        assertThat(result).isInstanceOf(ResolutionResult.Minted.class);
        assertThat(storage.size()).isEqualTo(1);
        assertThat(result.entity().aliases()).containsExactly(only.asAlias());
        assertThat(result.entity().attributes()).containsExactlyElementsOf(only.attributes());
    }

    @Test
    void sameSourceIdResolvedTwice_secondCallMatchesWithoutDuplicating() {
        Claim pim = sourceClaim("prod-001", "pim");

        ResolutionResult first = resolver.resolveOrMint(pim);
        ResolutionResult second = resolver.resolveOrMint(pim);

        assertThat(first).isInstanceOf(ResolutionResult.Minted.class);
        assertThat(second).isInstanceOf(ResolutionResult.Matched.class);
        assertThat(second.entity()).isEqualTo(first.entity());
        assertThat(storage.size()).isEqualTo(1);
        assertThat(conflictSink.events()).isEmpty();
    }

    @Test
    void multiSourceProduct_addAliasLinksAllSourceRecordsToOneEntity() {
        List<Claim> hero = fixtures.claimsFor("prod-001");
        assertThat(hero).hasSize(4);

        Entity minted = resolver.resolveOrMint(hero.get(0)).entity();
        for (int i = 1; i < hero.size(); i++) {
            resolver.addAlias(minted.id(), hero.get(i).source(), hero.get(i).sourceId());
        }

        assertThat(storage.size()).isEqualTo(1);
        Entity refreshed = resolver.getByUrn(minted.id());
        assertThat(refreshed.aliases()).hasSize(4);
        for (Claim claim : hero) {
            assertThat(resolver.findByAlias(claim.source(), claim.sourceId()))
                    .as("alias %s must resolve back to the canonical entity", claim.asAlias())
                    .contains(refreshed);
        }
    }

    @Test
    void conflictEvent_emittedWhenPimReassertsCorrectedBrand() {
        // prod-007 — original PIM record has brand "Globex"; updates.yaml
        // re-asserts the same pim-00007 alias with brand "Globex Industries".
        Claim baseline = sourceClaim("prod-007", "pim");
        Claim mutated = fixtures.updateClaims().stream()
                .filter(c -> c.source().name().equals("pim")
                        && c.sourceId().equals("pim-00007"))
                .findFirst()
                .orElseThrow();

        resolver.resolveOrMint(baseline);
        ResolutionResult result = resolver.resolveOrMint(mutated);

        assertThat(result).isInstanceOf(ResolutionResult.Matched.class);
        assertThat(result.entity().attributes())
                .containsExactlyElementsOf(baseline.attributes());

        assertThat(conflictSink.events()).hasSize(1);
        EntityAttributesConflicted event = conflictSink.events().get(0);
        assertThat(event.stored()).isEqualTo(result.entity());
        assertThat(event.incoming()).isEqualTo(mutated);
        assertThat(event.differences()).contains(
                new AttributeDiff("brand", "Globex", "Globex Industries"));
    }

    @Test
    void conflictEvent_emittedWhenSupplierReassertsRebrandedManufacturer() {
        // prod-008 — Initech acquired by Hooli; supplier re-asserts the
        // same MPN alias with a rebranded brand attribute.
        Claim baseline = sourceClaim("prod-008", "supplier");
        Claim rebranded = fixtures.updateClaims().stream()
                .filter(c -> c.source().name().equals("supplier")
                        && c.sourceId().equals("sup-initech-7700"))
                .findFirst()
                .orElseThrow();

        resolver.resolveOrMint(baseline);
        ResolutionResult result = resolver.resolveOrMint(rebranded);

        assertThat(result).isInstanceOf(ResolutionResult.Matched.class);
        assertThat(conflictSink.events()).hasSize(1);
        assertThat(conflictSink.events().get(0).differences()).contains(
                new AttributeDiff("brand", "Initech", "Hooli"));
    }

    @Test
    void fullDataset_naturalOrder_reconcilesToGroundTruth() {
        List<Claim> claims = new ArrayList<>(fixtures.allClaims());
        GroundTruthIngester.ingest(resolver, claims, fixtures.claimsByProduct());
        assertGraphMatchesGroundTruth();
    }

    @Test
    void fullDataset_reverseOrder_reconcilesToSameGroundTruth() {
        List<Claim> claims = new ArrayList<>(fixtures.allClaims());
        Collections.reverse(claims);
        GroundTruthIngester.ingest(resolver, claims, fixtures.claimsByProduct());
        assertGraphMatchesGroundTruth();
    }

    @Test
    void fullDataset_shuffledOrder_reconcilesToSameGroundTruth() {
        List<Claim> claims = new ArrayList<>(fixtures.allClaims());
        Collections.shuffle(claims, new Random(2026_05_11L));
        GroundTruthIngester.ingest(resolver, claims, fixtures.claimsByProduct());
        assertGraphMatchesGroundTruth();
    }

    @Test
    void idempotency_loadingTheDatasetTwiceProducesAnIdenticalEntityGraph() {
        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByProduct());
        Map<String, EntityId> firstPass = snapshotProductToEntity();
        int firstPassSize = storage.size();

        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByProduct());
        Map<String, EntityId> secondPass = snapshotProductToEntity();

        assertThat(storage.size()).isEqualTo(firstPassSize);
        assertThat(secondPass).isEqualTo(firstPass);
    }

    @Test
    void sizeAndColourVariants_remainDistinctEntities() {
        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByProduct());

        Entity largeBlue = resolver.findByAlias(SourceSystem.of("pim"), "pim-00001").orElseThrow();
        Entity smallBlue = resolver.findByAlias(SourceSystem.of("pim"), "pim-00005").orElseThrow();
        Entity largeRed  = resolver.findByAlias(SourceSystem.of("pim"), "pim-00006").orElseThrow();

        assertThat(largeBlue.id()).isNotEqualTo(smallBlue.id());
        assertThat(largeBlue.id()).isNotEqualTo(largeRed.id());
        assertThat(smallBlue.id()).isNotEqualTo(largeRed.id());

        // All three share the brand "Acme" but represent distinct products.
        assertThat(attribute(largeBlue, "brand")).isEqualTo("Acme");
        assertThat(attribute(smallBlue, "brand")).isEqualTo("Acme");
        assertThat(attribute(largeRed,  "brand")).isEqualTo("Acme");
    }

    @Test
    void twoProductsWithSimilarNames_remainDistinctEntities() {
        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByProduct());

        Entity proHammer = resolver.findByAlias(SourceSystem.of("pim"), "pim-00009").orElseThrow();
        Entity proHammerPlus = resolver.findByAlias(SourceSystem.of("pim"), "pim-00010").orElseThrow();

        assertThat(proHammer.id()).isNotEqualTo(proHammerPlus.id());
        assertThat(attribute(proHammer, "name")).isEqualTo("Vandelay Pro Hammer");
        assertThat(attribute(proHammerPlus, "name")).isEqualTo("Soylent Pro Hammer Plus");
    }

    @Test
    void updateClaimsBatch_emitsConflictEventsAgainstStoredAttributes() {
        // Stage 1 — full baseline ingestion, no conflicts expected.
        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByProduct());
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
        assertThat(storage.size()).isEqualTo(fixtures.productCount());

        for (Map.Entry<String, List<Claim>> product : fixtures.claimsByProduct().entrySet()) {
            Set<EntityId> entityIds = new HashSet<>();
            for (Claim claim : product.getValue()) {
                Entity resolved = resolver.findByAlias(claim.source(), claim.sourceId())
                        .orElseThrow(() -> new AssertionError(
                                "Product " + product.getKey() + " has unresolved alias "
                                        + claim.asAlias()));
                entityIds.add(resolved.id());
            }
            assertThat(entityIds)
                    .as("all aliases for %s must point at one entity", product.getKey())
                    .hasSize(1);
        }
    }

    private Map<String, EntityId> snapshotProductToEntity() {
        Map<String, EntityId> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<Claim>> entry : fixtures.claimsByProduct().entrySet()) {
            Claim first = entry.getValue().get(0);
            Entity entity = resolver.findByAlias(first.source(), first.sourceId())
                    .orElseThrow(() -> new AssertionError(
                            "Product " + entry.getKey() + " missing after ingest"));
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

    private Claim sourceClaim(String productId, String sourceName) {
        return fixtures.claimsFor(productId).stream()
                .filter(c -> c.source().name().equals(sourceName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Product " + productId + " has no " + sourceName + " record"));
    }

    private static Object attribute(Entity entity, String name) {
        return entity.attributes().stream()
                .filter(a -> a.name().equals(name))
                .map(MatchingAttribute::value)
                .findFirst()
                .orElse(null);
    }
}
