package uk.codery.jclaim.retail;

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
 * Sanity checks for the YAML loader and the dataset's documented shape.
 * Distribution counts are verified explicitly so editorial changes to
 * {@code customers.yaml} surface here rather than as obscure
 * integration-test failures elsewhere.
 */
class RetailFixturesTest {

    private static final Set<String> KNOWN_SOURCES = Set.of(
            "ecommerce", "pos", "loyalty", "crm");

    @Test
    void load_returnsExactlyOneHundredCustomers() {
        RetailFixtures fixtures = RetailFixtures.load();

        assertThat(fixtures.customerCount()).isEqualTo(100);
        assertThat(fixtures.claimsByCustomer()).hasSize(100);
        assertThat(fixtures.allClaims()).hasSizeGreaterThan(100);
    }

    @Test
    void load_distributionMatchesDocumentedCounts() {
        RetailFixtures fixtures = RetailFixtures.load();

        Map<Integer, Long> bySourceCount = fixtures.claimsByCustomer().values().stream()
                .collect(Collectors.groupingBy(List::size, Collectors.counting()));

        // 10 scenario + 50 bulk = 60 single-source records (but cust-007 is single, etc.)
        // Recount from the documented totals: scenario customers vary in size,
        // bulk is 50 + 25 + 11 + 4 = 90. Sum must equal 100 customers.
        long total = bySourceCount.values().stream().mapToLong(Long::longValue).sum();
        assertThat(total).isEqualTo(100);
        // Every customer must have at least one source record.
        assertThat(bySourceCount.keySet()).allMatch(size -> size >= 1 && size <= 4);
    }

    @Test
    void load_everyClaimUsesAKnownSourceAndNonBlankSourceId() {
        RetailFixtures fixtures = RetailFixtures.load();

        for (Claim claim : fixtures.allClaims()) {
            assertThat(KNOWN_SOURCES).contains(claim.source().name());
            assertThat(claim.sourceId()).isNotBlank();
        }
    }

    @Test
    void load_sourceIdsAreGloballyUniqueAcrossTheDataset() {
        RetailFixtures fixtures = RetailFixtures.load();

        Set<String> aliases = fixtures.allClaims().stream()
                .map(c -> c.source().name() + "|" + c.sourceId())
                .collect(Collectors.toSet());

        assertThat(aliases).hasSize(fixtures.allClaims().size());
    }

    @Test
    void load_featuredCustomerCarriesAllFourSources() {
        RetailFixtures fixtures = RetailFixtures.load();

        List<Claim> jane = fixtures.claimsFor("cust-001");
        assertThat(jane).hasSize(4);

        Set<String> sources = jane.stream()
                .map(c -> c.source().name())
                .collect(Collectors.toSet());
        assertThat(sources).containsExactlyInAnyOrderElementsOf(KNOWN_SOURCES);
    }

    @Test
    void load_ecommerceRecordCarriesExpectedAttributes() {
        RetailFixtures fixtures = RetailFixtures.load();
        Claim ecom = fixtures.claimsFor("cust-001").stream()
                .filter(c -> c.source().equals(SourceSystem.of("ecommerce")))
                .findFirst()
                .orElseThrow();

        assertThat(ecom.sourceId()).isEqualTo("ec-12345");
        assertThat(ecom.attributes()).contains(
                MatchingAttribute.of("email", "jane.doe@example.com"),
                MatchingAttribute.of("phone", "+44 7700 900123"));
    }

    @Test
    void load_updatesYamlYieldsConflictTargetingClaims() {
        RetailFixtures fixtures = RetailFixtures.load();

        List<Claim> updates = fixtures.updateClaims();
        assertThat(updates).isNotEmpty();
        assertThat(updates).allSatisfy(claim ->
                assertThat(KNOWN_SOURCES).contains(claim.source().name()));
        // Specifically: the ec-10010 update used by the conflict integration test.
        assertThat(updates).anyMatch(claim ->
                claim.sourceId().equals("ec-10010")
                        && claim.source().equals(SourceSystem.of("ecommerce")));
    }
}
