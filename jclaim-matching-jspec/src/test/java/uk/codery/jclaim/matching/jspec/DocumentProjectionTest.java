package uk.codery.jclaim.matching.jspec;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.matching.jspec.DocumentProjection.Projected;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.id.UuidV7;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentProjectionTest {

    private static Claim claim(MatchingAttribute... attrs) {
        return new Claim(new SourceSystem("crm"), "c-1", List.of(attrs));
    }

    private static Entity candidate(MatchingAttribute... attrs) {
        Instant now = Instant.now();
        return new Entity(EntityId.of(UuidV7.generate()), "K7M2-9X4P-N", List.of(),
                List.of(attrs), null, now, now);
    }

    @Test
    void defaultProjectionNestsClaimAndCandidateAttributesUnderNamedKeys() {
        Projected p = DocumentProjection.defaults().project(
                claim(new MatchingAttribute("email", "a@b.com")),
                candidate(new MatchingAttribute("email", "a@b.com")));

        assertThat(p.target()).isEqualTo(Map.of("claim", Map.of("email", "a@b.com")));
        assertThat(p.context()).isEqualTo(Map.of("candidate", Map.of("email", "a@b.com")));
    }

    @Test
    void foldsMultipleAttributesIntoAFlatNameToValueMap() {
        Projected p = DocumentProjection.defaults().project(
                claim(new MatchingAttribute("email", "a@b.com"),
                        new MatchingAttribute("country", "UK")),
                candidate(new MatchingAttribute("email", "x@y.com")));

        assertThat(p.target()).isEqualTo(
                Map.of("claim", Map.of("email", "a@b.com", "country", "UK")));
        assertThat(p.context()).isEqualTo(
                Map.of("candidate", Map.of("email", "x@y.com")));
    }

    @Test
    void onDuplicateAttributeNamesLastValueWins() {
        Projected p = DocumentProjection.defaults().project(
                claim(new MatchingAttribute("email", "first@b.com"),
                        new MatchingAttribute("email", "last@b.com")),
                candidate());

        assertThat(p.target()).isEqualTo(Map.of("claim", Map.of("email", "last@b.com")));
    }

    @Test
    void emptyAttributeListsProjectToEmptyInnerMaps() {
        Projected p = DocumentProjection.defaults().project(claim(), candidate());

        assertThat(p.target()).isEqualTo(Map.of("claim", Map.of()));
        assertThat(p.context()).isEqualTo(Map.of("candidate", Map.of()));
    }
}
