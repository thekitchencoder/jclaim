package uk.codery.jclaim.property;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.codery.jclaim.event.AttributeDiff;
import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.fixtures.DeterministicUuids;
import uk.codery.jclaim.fixtures.GroundTruthIngester;
import uk.codery.jclaim.fixtures.RecordingMatchSink;
import uk.codery.jclaim.id.CrockfordPublicIdGenerator;
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
 * End-to-end reconciliation contract against the synthetic UK property
 * dataset, parameterised by storage factory. Exercises multi-source property
 * reconciliation across PAF, Ordnance Survey, Land Registry, and council
 * tax — including provisional new builds that receive UPRN assignments later.
 */
public abstract class AbstractPropertyReconciliationTest {

    protected PropertyFixtures fixtures;
    protected EntityStorage storage;
    protected RecordingMatchSink matchSink;
    protected EntityResolver resolver;

    /** Returns a fresh, empty {@link EntityStorage} for the test about to run. */
    protected abstract EntityStorage newStorage();

    @BeforeEach
    final void setUp() {
        fixtures = PropertyFixtures.load();
        storage = newStorage();
        matchSink = new RecordingMatchSink();
        resolver = DefaultEntityResolver.builder(storage)
                .namespace("codery")
                .uuidSupplier(DeterministicUuids.supplier())
                .publicIdGenerator(new CrockfordPublicIdGenerator(new Random(44)))
                .clock(Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC))
                .matchEventSink(matchSink)
                .build();
    }

    @Test
    void preUprnNewBuild_resolveOrMint_mintsExactlyOneEntity() {
        // prop-009 — PAF-only provisional record before UPRN allocation.
        Claim only = fixtures.claimsFor("prop-009").get(0);

        ResolutionResult result = resolver.resolveOrMint(only);

        assertThat(result).isInstanceOf(ResolutionResult.Minted.class);
        assertThat(countEntitiesReachableVia(List.of(only))).isEqualTo(1);
        assertThat(result.entity().aliases()).containsExactly(only.asAlias());
        assertThat(result.entity().attributes()).containsExactlyElementsOf(only.attributes());
    }

    @Test
    void sameSourceIdResolvedTwice_secondCallMatchesWithoutDuplicating() {
        Claim paf = sourceClaim("prop-001", "royal_mail_paf");

        ResolutionResult first = resolver.resolveOrMint(paf);
        ResolutionResult second = resolver.resolveOrMint(paf);

        assertThat(first).isInstanceOf(ResolutionResult.Minted.class);
        assertThat(second).isInstanceOf(ResolutionResult.Matched.class);
        assertThat(second.entity()).isEqualTo(first.entity());
        assertThat(countEntitiesReachableVia(List.of(paf))).isEqualTo(1);
        assertThat(matchSink.events()).isEmpty();
    }

    @Test
    void multiSourceProperty_addAliasLinksAllSourceRecordsToOneEntity() {
        List<Claim> hero = fixtures.claimsFor("prop-001");
        assertThat(hero).hasSize(4);

        Entity minted = resolver.resolveOrMint(hero.get(0)).entity();
        for (int i = 1; i < hero.size(); i++) {
            resolver.addAlias(minted.id(), hero.get(i).source(), hero.get(i).sourceId());
        }

        assertThat(countEntitiesReachableVia(hero)).isEqualTo(1);
        Entity refreshed = resolver.getByUrn(minted.id());
        assertThat(refreshed.aliases()).hasSize(4);
        for (Claim claim : hero) {
            assertThat(resolver.findByAlias(claim.source(), claim.sourceId()))
                    .as("alias %s must resolve back to the canonical entity", claim.asAlias())
                    .contains(refreshed);
        }
    }

    @Test
    void conflictEvent_emittedWhenPafReassertsRenumberedPostcode() {
        // prop-008 — original PAF record has postcode "EC2A 4DP"; updates.yaml
        // re-asserts the same PAF alias with postcode "EC2A 5DP" after a
        // postal-district boundary change.
        Claim baseline = sourceClaim("prop-008", "royal_mail_paf");
        Claim mutated = fixtures.updateClaims().stream()
                .filter(c -> c.source().name().equals("royal_mail_paf")
                        && c.sourceId().equals("paf-100099000080"))
                .findFirst()
                .orElseThrow();

        resolver.resolveOrMint(baseline);
        ResolutionResult result = resolver.resolveOrMint(mutated);

        assertThat(result).isInstanceOf(ResolutionResult.Matched.class);
        assertThat(result.entity().attributes())
                .containsExactlyElementsOf(baseline.attributes());

        assertThat(matchSink.events()).hasSize(1);
        EntityAttributesConflicted event = matchSink.events().get(0);
        assertThat(event.stored()).isEqualTo(result.entity());
        assertThat(event.claim()).isEqualTo(mutated);
        assertThat(event.differingValues()).contains(
                new AttributeDiff("postcode", "EC2A 4DP", "EC2A 5DP"));
    }

    @Test
    void conflictEvent_emittedWhenNewBuildReceivesUprnAssignment() {
        // prop-009 — provisional new build, originally no UPRN; updates.yaml
        // re-asserts the same PAF alias with the freshly-assigned UPRN plus a
        // tidied-up address line.
        Claim baseline = sourceClaim("prop-009", "royal_mail_paf");
        Claim withUprn = fixtures.updateClaims().stream()
                .filter(c -> c.source().name().equals("royal_mail_paf")
                        && c.sourceId().equals("paf-PROV-009"))
                .findFirst()
                .orElseThrow();

        resolver.resolveOrMint(baseline);
        ResolutionResult result = resolver.resolveOrMint(withUprn);

        assertThat(result).isInstanceOf(ResolutionResult.Matched.class);
        assertThat(matchSink.events()).hasSize(1);
        // The freshly-assigned uprn is an attribute the entity never carried —
        // additive, not a conflict — so it must NOT appear as a diff.
        assertThat(matchSink.events().get(0).differingValues())
                .noneMatch(d -> d.name().equals("uprn"));
        // The address line, however, exists on both sides with a changed
        // value, so it is a genuine conflict.
        assertThat(matchSink.events().get(0).differingValues()).contains(
                new AttributeDiff("address_line_1", "Plot 9, Hanover Place", "9 Hanover Place"));
    }

    @Test
    void fullDataset_naturalOrder_reconcilesToGroundTruth() {
        List<Claim> claims = new ArrayList<>(fixtures.allClaims());
        GroundTruthIngester.ingest(resolver, claims, fixtures.claimsByProperty());
        assertGraphMatchesGroundTruth();
    }

    @Test
    void fullDataset_reverseOrder_reconcilesToSameGroundTruth() {
        List<Claim> claims = new ArrayList<>(fixtures.allClaims());
        Collections.reverse(claims);
        GroundTruthIngester.ingest(resolver, claims, fixtures.claimsByProperty());
        assertGraphMatchesGroundTruth();
    }

    @Test
    void fullDataset_shuffledOrder_reconcilesToSameGroundTruth() {
        List<Claim> claims = new ArrayList<>(fixtures.allClaims());
        Collections.shuffle(claims, new Random(2026_05_11L));
        GroundTruthIngester.ingest(resolver, claims, fixtures.claimsByProperty());
        assertGraphMatchesGroundTruth();
    }

    @Test
    void idempotency_loadingTheDatasetTwiceProducesAnIdenticalEntityGraph() {
        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByProperty());
        Map<String, EntityId> firstPass = snapshotPropertyToEntity();
        int firstPassSize = countEntitiesReachableVia(fixtures.allClaims());

        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByProperty());
        Map<String, EntityId> secondPass = snapshotPropertyToEntity();

        assertThat(countEntitiesReachableVia(fixtures.allClaims())).isEqualTo(firstPassSize);
        assertThat(secondPass).isEqualTo(firstPass);
    }

    @Test
    void threeFlatsInOneBuilding_remainDistinctEntities() {
        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByProperty());

        Entity flat1 = resolver.findByAlias(
                SourceSystem.of("royal_mail_paf"), "paf-100099000041").orElseThrow();
        Entity flat2 = resolver.findByAlias(
                SourceSystem.of("royal_mail_paf"), "paf-100099000051").orElseThrow();
        Entity flat3 = resolver.findByAlias(
                SourceSystem.of("royal_mail_paf"), "paf-100099000061").orElseThrow();

        Set<EntityId> ids = Set.of(flat1.id(), flat2.id(), flat3.id());
        assertThat(ids).hasSize(3);
        // All three share the postcode and building number.
        assertThat(attribute(flat1, "postcode")).isEqualTo("NW1 6XE");
        assertThat(attribute(flat2, "postcode")).isEqualTo("NW1 6XE");
        assertThat(attribute(flat3, "postcode")).isEqualTo("NW1 6XE");
    }

    @Test
    void twoPropertiesWithSimilarAddresses_remainDistinctEntities() {
        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByProperty());

        Entity acacia10  = resolver.findByAlias(
                SourceSystem.of("royal_mail_paf"), "paf-100099000100").orElseThrow();
        Entity acacia10A = resolver.findByAlias(
                SourceSystem.of("royal_mail_paf"), "paf-100099000260").orElseThrow();

        assertThat(acacia10.id()).isNotEqualTo(acacia10A.id());
        assertThat(attribute(acacia10,  "address_line_1")).isEqualTo("10 Acacia Avenue");
        assertThat(attribute(acacia10A, "address_line_1")).isEqualTo("10A Acacia Avenue");
    }

    @Test
    void addressRepresentationVariation_doesNotAutoMerge_butAddAliasLinksRecords() {
        // Without addAlias, the two prop-007 records would mint two entities,
        // since exact-alias matching ignores the shared UPRN attribute.
        List<Claim> records = fixtures.claimsFor("prop-007");
        EntityStorage isolated = newStorage();
        EntityResolver isolatedResolver = DefaultEntityResolver.builder(isolated)
                .uuidSupplier(DeterministicUuids.supplier())
                .publicIdGenerator(new CrockfordPublicIdGenerator(new Random(98)))
                .build();
        for (Claim claim : records) {
            isolatedResolver.resolveOrMint(claim);
        }
        int isolatedCount = 0;
        Set<EntityId> isolatedIds = new HashSet<>();
        for (Claim claim : records) {
            isolated.findByAlias(claim.asAlias()).ifPresent(e -> isolatedIds.add(e.id()));
        }
        isolatedCount = isolatedIds.size();
        assertThat(isolatedCount).isEqualTo(records.size());

        // With the ground-truth-aware ingest, both records land on one entity.
        GroundTruthIngester.ingest(resolver, records, Map.of("prop-007", records));
        assertThat(countEntitiesReachableVia(records)).isEqualTo(1);
    }

    @Test
    void updateClaimsBatch_emitsConflictEventsAgainstStoredAttributes() {
        // Stage 1 — full baseline ingestion, no conflicts expected.
        GroundTruthIngester.ingest(resolver, fixtures.allClaims(), fixtures.claimsByProperty());
        assertThat(matchSink.events())
                .as("baseline ingest must not emit conflicts")
                .isEmpty();

        // Stage 2 — apply updates and assert each produces a Matched result.
        // Update claims that change a shared attribute value emit a conflict;
        // claims that only add previously-unseen attributes are additive and
        // emit nothing. Stored attributes must remain untouched throughout.
        Map<Alias, List<MatchingAttribute>> storedBefore = snapshotAttributesByAlias();
        for (Claim update : fixtures.updateClaims()) {
            ResolutionResult result = resolver.resolveOrMint(update);
            assertThat(result).isInstanceOf(ResolutionResult.Matched.class);
        }
        assertThat(matchSink.events())
                .as("at least one update diverges on a shared attribute")
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(fixtures.updateClaims().size());
        assertThat(snapshotAttributesByAlias()).isEqualTo(storedBefore);
    }

    // ── Assertion helpers ───────────────────────────────────────────────

    private void assertGraphMatchesGroundTruth() {
        assertThat(countEntitiesReachableVia(fixtures.allClaims()))
                .isEqualTo(fixtures.propertyCount());

        for (Map.Entry<String, List<Claim>> property : fixtures.claimsByProperty().entrySet()) {
            Set<EntityId> entityIds = new HashSet<>();
            for (Claim claim : property.getValue()) {
                Entity resolved = resolver.findByAlias(claim.source(), claim.sourceId())
                        .orElseThrow(() -> new AssertionError(
                                "Property " + property.getKey() + " has unresolved alias "
                                        + claim.asAlias()));
                entityIds.add(resolved.id());
            }
            assertThat(entityIds)
                    .as("all aliases for %s must point at one entity", property.getKey())
                    .hasSize(1);
        }
    }

    private Map<String, EntityId> snapshotPropertyToEntity() {
        Map<String, EntityId> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<Claim>> entry : fixtures.claimsByProperty().entrySet()) {
            Claim first = entry.getValue().get(0);
            Entity entity = resolver.findByAlias(first.source(), first.sourceId())
                    .orElseThrow(() -> new AssertionError(
                            "Property " + entry.getKey() + " missing after ingest"));
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

    private Claim sourceClaim(String propertyId, String sourceName) {
        return fixtures.claimsFor(propertyId).stream()
                .filter(c -> c.source().name().equals(sourceName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Property " + propertyId + " has no " + sourceName + " record"));
    }

    private int countEntitiesReachableVia(Collection<Claim> claims) {
        Set<EntityId> ids = new HashSet<>();
        for (Claim claim : claims) {
            storage.findByAlias(claim.asAlias()).ifPresent(e -> ids.add(e.id()));
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
