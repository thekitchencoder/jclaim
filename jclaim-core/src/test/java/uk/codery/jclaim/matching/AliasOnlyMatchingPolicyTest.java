package uk.codery.jclaim.matching;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AliasOnlyMatchingPolicyTest {

    private static final MatchingPolicy POLICY = MatchingPolicy.aliasOnly();

    private final Claim claim = new Claim(new SourceSystem("ecommerce"), "C-1",
        List.of(new MatchingAttribute("email", "a@b.com")));

    @Test
    void candidateCarryingTheClaimAliasMatches() {
        Entity candidate = entityWithAliases(List.of(claim.asAlias()));

        assertThat(POLICY.evaluate(claim, candidate)).isEqualTo(TriState.MATCHED);
    }

    @Test
    void candidateWithoutTheClaimAliasDoesNotMatch() {
        Entity candidate = entityWithAliases(List.of(Alias.of("crm", "OTHER")));

        assertThat(POLICY.evaluate(claim, candidate)).isEqualTo(TriState.NOT_MATCHED);
    }

    @Test
    void neverReturnsUndetermined() {
        Entity withAlias = entityWithAliases(List.of(claim.asAlias()));
        Entity withoutAlias = entityWithAliases(List.of(Alias.of("crm", "OTHER")));

        assertThat(POLICY.evaluate(claim, withAlias)).isNotEqualTo(TriState.UNDETERMINED);
        assertThat(POLICY.evaluate(claim, withoutAlias)).isNotEqualTo(TriState.UNDETERMINED);
    }

    @Test
    void aliasOnlyIsAStatelessSharedSingleton() {
        assertThat(MatchingPolicy.aliasOnly()).isSameAs(MatchingPolicy.aliasOnly());
    }

    private static Entity entityWithAliases(List<Alias> aliases) {
        Instant now = Instant.now();
        return new Entity(
            EntityId.of(UUID.randomUUID()),
            "K7M2-9X4P-N",
            aliases,
            List.of(),
            null,
            now,
            now);
    }
}
