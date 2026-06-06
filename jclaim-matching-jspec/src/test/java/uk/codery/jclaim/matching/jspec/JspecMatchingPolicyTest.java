package uk.codery.jclaim.matching.jspec;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.id.UuidV7;
import uk.codery.jclaim.matching.MatchingPolicy;
import uk.codery.jclaim.matching.TriState;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jspec.model.QueryCriterion;
import uk.codery.jspec.model.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JspecMatchingPolicyTest {

    /** Two-criterion spec: claim email/country must equal candidate email/country. */
    private static Specification twoCriterionSpec() {
        return new Specification("claim-vs-candidate", List.of(
                new QueryCriterion("same-email", Map.of(
                        "claim.email", Map.of("$eq", Map.of("$contextPath", "candidate.email")))),
                new QueryCriterion("same-country", Map.of(
                        "claim.country", Map.of("$eq", Map.of("$contextPath", "candidate.country"))))));
    }

    private static Claim claim(String email, String country) {
        return new Claim(new SourceSystem("crm"), "c-1", List.of(
                new MatchingAttribute("email", email),
                new MatchingAttribute("country", country)));
    }

    private static Entity candidate(MatchingAttribute... attrs) {
        Instant now = Instant.now();
        return new Entity(EntityId.of(UuidV7.generate()), "K7M2-9X4P-N", List.of(),
                List.of(attrs), null, now, now);
    }

    @Test
    void alignedClaimAndCandidateMatchOnAllCriteria() {
        MatchingPolicy policy = JspecMatchingPolicy.builder().spec(twoCriterionSpec()).build();

        TriState result = policy.evaluate(
                claim("a@b.com", "UK"),
                candidate(new MatchingAttribute("email", "a@b.com"),
                        new MatchingAttribute("country", "UK")));

        assertThat(result).isEqualTo(TriState.MATCHED);
    }

    @Test
    void divergingValueOnOneCriterionYieldsNotMatched() {
        MatchingPolicy policy = JspecMatchingPolicy.builder().spec(twoCriterionSpec()).build();

        TriState result = policy.evaluate(
                claim("a@b.com", "UK"),
                candidate(new MatchingAttribute("email", "a@b.com"),
                        new MatchingAttribute("country", "FR")));

        assertThat(result).isEqualTo(TriState.NOT_MATCHED);
    }

    @Test
    void missingCandidateAttributeYieldsUndetermined() {
        MatchingPolicy policy = JspecMatchingPolicy.builder().spec(twoCriterionSpec()).build();

        // candidate has no country -> $contextPath candidate.country is missing -> UNDETERMINED,
        // and no criterion NOT_MATCHED (email aligns), so the pair is UNDETERMINED overall.
        TriState result = policy.evaluate(
                claim("a@b.com", "UK"),
                candidate(new MatchingAttribute("email", "a@b.com")));

        assertThat(result).isEqualTo(TriState.UNDETERMINED);
    }

    @Test
    void builderRequiresASpec() {
        assertThatThrownBy(() -> JspecMatchingPolicy.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("spec");
    }

    @Test
    void fromStringLoadsAYamlSpecAndEvaluatesIt() {
        String yaml = """
                id: claim-vs-candidate
                criteria:
                  - id: same-email
                    query:
                      claim.email:
                        $eq:
                          $contextPath: candidate.email
                """;
        MatchingPolicy policy = JspecMatchingPolicy.fromString(yaml);

        assertThat(policy.evaluate(
                claim("a@b.com", "UK"),
                candidate(new MatchingAttribute("email", "a@b.com"))))
                .isEqualTo(TriState.MATCHED);
        assertThat(policy.evaluate(
                claim("a@b.com", "UK"),
                candidate(new MatchingAttribute("email", "other@b.com"))))
                .isEqualTo(TriState.NOT_MATCHED);
    }

    @Test
    void fromResourceLoadsAYamlSpecFromTheClasspath() {
        MatchingPolicy policy = JspecMatchingPolicy.fromResource("/matching/email-and-country.yaml");

        assertThat(policy.evaluate(
                claim("a@b.com", "UK"),
                candidate(new MatchingAttribute("email", "a@b.com"),
                        new MatchingAttribute("country", "UK"))))
                .isEqualTo(TriState.MATCHED);
    }

    @Test
    void defaultBlockingKeysAreEmpty() {
        MatchingPolicy policy = JspecMatchingPolicy.builder().spec(twoCriterionSpec()).build();
        assertThat(policy.blockingKeys()).isEmpty();
    }

    @Test
    void builderCarriesBlockingKeys() {
        MatchingPolicy policy = JspecMatchingPolicy.builder()
                .spec(twoCriterionSpec())
                .blockingKeys(List.of("email", "country"))
                .build();
        assertThat(policy.blockingKeys()).containsExactlyInAnyOrder("email", "country");
    }

    @Test
    void fromResourceWithKeysCarriesThemOntoThePolicy() {
        MatchingPolicy policy = JspecMatchingPolicy.fromResource(
                "/matching/email-and-country.yaml", List.of("email"));
        assertThat(policy.blockingKeys()).containsExactly("email");
    }

    @Test
    void fromStringWithKeysCarriesThemOntoThePolicy() {
        String yaml = """
                id: claim-vs-candidate
                criteria:
                  - id: same-email
                    query:
                      claim.email:
                        $eq:
                          $contextPath: candidate.email
                """;
        MatchingPolicy policy = JspecMatchingPolicy.fromString(yaml, List.of("email"));
        assertThat(policy.blockingKeys()).containsExactly("email");
    }

    @Test
    void blankBlockingKeyIsRejected() {
        assertThatThrownBy(() -> JspecMatchingPolicy.builder()
                .spec(twoCriterionSpec())
                .blockingKeys(List.of("email", "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocking key");
    }

    @Test
    void returnedBlockingKeysSetIsImmutable() {
        Set<String> keys = JspecMatchingPolicy.builder()
                .spec(twoCriterionSpec())
                .blockingKeys(List.of("email"))
                .build()
                .blockingKeys();
        assertThatThrownBy(() -> keys.add("country"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
