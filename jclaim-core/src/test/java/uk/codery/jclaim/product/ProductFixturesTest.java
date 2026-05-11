package uk.codery.jclaim.product;

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
 * Sanity checks for the YAML loader and the product dataset's documented
 * shape. Distribution counts are verified explicitly so editorial
 * changes to {@code products.yaml} surface here rather than as obscure
 * integration-test failures elsewhere.
 */
class ProductFixturesTest {

    private static final Set<String> KNOWN_SOURCES = Set.of(
            "pim", "warehouse", "marketplace", "supplier");

    @Test
    void load_returnsExactlyOneHundredProducts() {
        ProductFixtures fixtures = ProductFixtures.load();

        assertThat(fixtures.productCount()).isEqualTo(100);
        assertThat(fixtures.claimsByProduct()).hasSize(100);
        assertThat(fixtures.allClaims()).hasSizeGreaterThan(100);
    }

    @Test
    void load_distributionMatchesDocumentedCounts() {
        ProductFixtures fixtures = ProductFixtures.load();

        Map<Integer, Long> bySourceCount = fixtures.claimsByProduct().values().stream()
                .collect(Collectors.groupingBy(List::size, Collectors.counting()));

        long total = bySourceCount.values().stream().mapToLong(Long::longValue).sum();
        assertThat(total).isEqualTo(100);
        // Every product must have at least one source record.
        assertThat(bySourceCount.keySet()).allMatch(size -> size >= 1 && size <= 4);
    }

    @Test
    void load_everyClaimUsesAKnownSourceAndNonBlankSourceId() {
        ProductFixtures fixtures = ProductFixtures.load();

        for (Claim claim : fixtures.allClaims()) {
            assertThat(KNOWN_SOURCES).contains(claim.source().name());
            assertThat(claim.sourceId()).isNotBlank();
        }
    }

    @Test
    void load_sourceIdsAreGloballyUniqueAcrossTheDataset() {
        ProductFixtures fixtures = ProductFixtures.load();

        Set<String> aliases = fixtures.allClaims().stream()
                .map(c -> c.source().name() + "|" + c.sourceId())
                .collect(Collectors.toSet());

        assertThat(aliases).hasSize(fixtures.allClaims().size());
    }

    @Test
    void load_featuredProductCarriesAllFourSources() {
        ProductFixtures fixtures = ProductFixtures.load();

        List<Claim> hero = fixtures.claimsFor("prod-001");
        assertThat(hero).hasSize(4);

        Set<String> sources = hero.stream()
                .map(c -> c.source().name())
                .collect(Collectors.toSet());
        assertThat(sources).containsExactlyInAnyOrderElementsOf(KNOWN_SOURCES);
    }

    @Test
    void load_pimRecordCarriesExpectedAttributes() {
        ProductFixtures fixtures = ProductFixtures.load();
        Claim pim = fixtures.claimsFor("prod-001").stream()
                .filter(c -> c.source().equals(SourceSystem.of("pim")))
                .findFirst()
                .orElseThrow();

        assertThat(pim.sourceId()).isEqualTo("pim-00001");
        assertThat(pim.attributes()).contains(
                MatchingAttribute.of("gtin", "5099900000017"),
                MatchingAttribute.of("brand", "Acme"),
                MatchingAttribute.of("manufacturer_part_number", "MPN-A-1234"));
    }

    @Test
    void load_updatesYamlYieldsConflictTargetingClaims() {
        ProductFixtures fixtures = ProductFixtures.load();

        List<Claim> updates = fixtures.updateClaims();
        assertThat(updates).isNotEmpty();
        assertThat(updates).allSatisfy(claim ->
                assertThat(KNOWN_SOURCES).contains(claim.source().name()));
        // Specifically: the pim-00007 update used by the conflict integration test.
        assertThat(updates).anyMatch(claim ->
                claim.sourceId().equals("pim-00007")
                        && claim.source().equals(SourceSystem.of("pim")));
    }
}
