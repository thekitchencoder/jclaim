package uk.codery.jclaim.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelValidationTest {

    private static final EntityId SOME_ID = EntityId.of(
            UUID.fromString("01900000-0000-7000-8000-00000000000a"));

    @Test
    void sourceSystem_rejectsBlankName() {
        assertThatThrownBy(() -> SourceSystem.of(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceSystem(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void alias_factoriesProduceEqualValues() {
        Alias direct = new Alias(SourceSystem.of("crm"), "abc");
        Alias viaSystem = Alias.of(SourceSystem.of("crm"), "abc");
        Alias viaName = Alias.of("crm", "abc");
        assertThat(direct).isEqualTo(viaSystem).isEqualTo(viaName);
    }

    @Test
    void alias_rejectsBlankSourceId() {
        assertThatThrownBy(() -> Alias.of("crm", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchingAttribute_rejectsBlankNameOrNullValue() {
        assertThatThrownBy(() -> MatchingAttribute.of("", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MatchingAttribute.of("k", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void claim_rejectsBlankSourceId() {
        assertThatThrownBy(() -> new Claim(SourceSystem.of("crm"), " ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claim_asAliasReflectsSourceAndSourceId() {
        Claim claim = new Claim(SourceSystem.of("crm"), "X", List.of());
        assertThat(claim.asAlias()).isEqualTo(Alias.of("crm", "X"));
    }

    @Test
    void entity_withAliasAddsOnceAndIsIdempotent() {
        Alias a = Alias.of("crm", "X");
        Entity entity = new Entity(
                SOME_ID, "0000-0000-0", List.of(a), List.of(), null,
                Instant.EPOCH, Instant.EPOCH);

        Entity sameAlias = entity.withAlias(a, Instant.EPOCH);
        assertThat(sameAlias).isSameAs(entity);

        Alias b = Alias.of("pos", "Y");
        Entity twoAliases = entity.withAlias(b, Instant.EPOCH.plusSeconds(1));
        assertThat(twoAliases.aliases()).containsExactly(a, b);
        assertThat(twoAliases.updatedAt()).isEqualTo(Instant.EPOCH.plusSeconds(1));
    }

    @Test
    void entity_rejectsBlankHumanId() {
        assertThatThrownBy(() -> new Entity(
                SOME_ID, " ", List.of(), List.of(), null,
                Instant.EPOCH, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entity_supersededByOptWrapsNullableField() {
        Entity none = new Entity(SOME_ID, "0000-0000-0", List.of(), List.of(),
                null, Instant.EPOCH, Instant.EPOCH);
        assertThat(none.supersededByOpt()).isEmpty();

        EntityId successor = EntityId.of(UUID.fromString("01900000-0000-7000-8000-00000000000b"));
        Entity superseded = new Entity(SOME_ID, "0000-0000-0", List.of(), List.of(),
                successor, Instant.EPOCH, Instant.EPOCH);
        assertThat(superseded.supersededByOpt()).contains(successor);
    }

    @Test
    void resolutionResult_rejectsNullEntity() {
        assertThatThrownBy(() -> new ResolutionResult.Matched(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResolutionResult.Minted(null))
                .isInstanceOf(NullPointerException.class);
    }
}
