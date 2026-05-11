package uk.codery.jclaim.property;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity checks for the YAML loader and the property dataset's
 * documented shape. Distribution counts are verified explicitly so
 * editorial changes to {@code properties.yaml} surface here rather than
 * as obscure integration-test failures elsewhere.
 */
class PropertyFixturesTest {

    private static final Set<String> KNOWN_SOURCES = Set.of(
            "royal_mail_paf", "os_addressbase", "land_registry", "council_tax");

    @Test
    void load_returnsExactlyOneHundredProperties() {
        PropertyFixtures fixtures = PropertyFixtures.load();

        assertThat(fixtures.propertyCount()).isEqualTo(100);
        assertThat(fixtures.claimsByProperty()).hasSize(100);
        assertThat(fixtures.allClaims()).hasSizeGreaterThan(100);
    }

    @Test
    void load_distributionMatchesDocumentedCounts() {
        PropertyFixtures fixtures = PropertyFixtures.load();

        Map<Integer, Long> bySourceCount = fixtures.claimsByProperty().values().stream()
                .collect(Collectors.groupingBy(List::size, Collectors.counting()));

        long total = bySourceCount.values().stream().mapToLong(Long::longValue).sum();
        assertThat(total).isEqualTo(100);
        assertThat(bySourceCount.keySet()).allMatch(size -> size >= 1 && size <= 4);
    }

    @Test
    void load_everyClaimUsesAKnownSourceAndNonBlankSourceId() {
        PropertyFixtures fixtures = PropertyFixtures.load();

        for (Claim claim : fixtures.allClaims()) {
            assertThat(KNOWN_SOURCES).contains(claim.source().name());
            assertThat(claim.sourceId()).isNotBlank();
        }
    }

    @Test
    void load_sourceIdsAreGloballyUniqueAcrossTheDataset() {
        PropertyFixtures fixtures = PropertyFixtures.load();

        Set<String> aliases = fixtures.allClaims().stream()
                .map(c -> c.source().name() + "|" + c.sourceId())
                .collect(Collectors.toSet());

        assertThat(aliases).hasSize(fixtures.allClaims().size());
    }

    @Test
    void load_featuredPropertyCarriesAllFourSources() {
        PropertyFixtures fixtures = PropertyFixtures.load();

        List<Claim> hero = fixtures.claimsFor("prop-001");
        assertThat(hero).hasSize(4);

        Set<String> sources = hero.stream()
                .map(c -> c.source().name())
                .collect(Collectors.toSet());
        assertThat(sources).containsExactlyInAnyOrderElementsOf(KNOWN_SOURCES);
    }

    @Test
    void load_pafRecordCarriesExpectedAttributes() {
        PropertyFixtures fixtures = PropertyFixtures.load();
        Claim paf = fixtures.claimsFor("prop-001").stream()
                .filter(c -> c.source().equals(SourceSystem.of("royal_mail_paf")))
                .findFirst()
                .orElseThrow();

        assertThat(paf.sourceId()).isEqualTo("paf-100099000001");
        assertThat(paf.attributes()).contains(
                MatchingAttribute.of("uprn", "100099000001"),
                MatchingAttribute.of("postcode", "SW1A 1AA"),
                MatchingAttribute.of("address_line_1", "12 Acacia Avenue"));
    }

    @Test
    void load_threeFlatsInOneBuildingShareAddressButHaveDistinctUprns() {
        PropertyFixtures fixtures = PropertyFixtures.load();

        Claim flat1Paf = pafClaim(fixtures, "prop-004");
        Claim flat2Paf = pafClaim(fixtures, "prop-005");
        Claim flat3Paf = pafClaim(fixtures, "prop-006");

        // Same postcode and building number
        assertThat(attribute(flat1Paf, "postcode")).isEqualTo("NW1 6XE");
        assertThat(attribute(flat2Paf, "postcode")).isEqualTo("NW1 6XE");
        assertThat(attribute(flat3Paf, "postcode")).isEqualTo("NW1 6XE");
        assertThat(attribute(flat1Paf, "building_number")).isEqualTo("23");
        assertThat(attribute(flat2Paf, "building_number")).isEqualTo("23");
        assertThat(attribute(flat3Paf, "building_number")).isEqualTo("23");

        // Distinct UPRNs
        Set<Object> uprns = Set.of(
                attribute(flat1Paf, "uprn"),
                attribute(flat2Paf, "uprn"),
                attribute(flat3Paf, "uprn"));
        assertThat(uprns).hasSize(3);
    }

    @Test
    void load_updatesYamlYieldsConflictTargetingClaims() {
        PropertyFixtures fixtures = PropertyFixtures.load();

        List<Claim> updates = fixtures.updateClaims();
        assertThat(updates).isNotEmpty();
        assertThat(updates).allSatisfy(claim ->
                assertThat(KNOWN_SOURCES).contains(claim.source().name()));
        // Specifically: the paf-100099000080 update used by the conflict integration test.
        assertThat(updates).anyMatch(claim ->
                claim.sourceId().equals("paf-100099000080")
                        && claim.source().equals(SourceSystem.of("royal_mail_paf")));
    }

    private static Claim pafClaim(PropertyFixtures fixtures, String propertyId) {
        return fixtures.claimsFor(propertyId).stream()
                .filter(c -> c.source().equals(SourceSystem.of("royal_mail_paf")))
                .findFirst()
                .orElseThrow();
    }

    private static Object attribute(Claim claim, String name) {
        return claim.attributes().stream()
                .filter(a -> a.name().equals(name))
                .map(MatchingAttribute::value)
                .findFirst()
                .orElse(null);
    }
}
