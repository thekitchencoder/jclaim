package uk.codery.jclaim.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JclaimPropertiesTest {

    @Test
    void bindsDefaults() {
        JclaimProperties props = bind(Map.of());
        assertThat(props.urn().namespace()).isEqualTo("codery");
        assertThat(props.storage().type()).isEqualTo(JclaimProperties.StorageType.AUTO);
        assertThat(props.storage().mongo().database()).isEqualTo("jclaim");
        assertThat(props.storage().mongo().collectionName()).isEqualTo("jclaim_entities");
        assertThat(props.storage().mongo().createIndexes()).isTrue();
        assertThat(props.storage().postgres().applySchema()).isTrue();
        assertThat(props.matchSink().type()).isEqualTo(JclaimProperties.MatchSinkType.SPRING_EVENTS);
        assertThat(props.metrics().enabled()).isTrue();
        assertThat(props.health().enabled()).isTrue();
    }

    @Test
    void bindsOverrides() {
        JclaimProperties props = bind(Map.of(
                "jclaim.urn.namespace", "acme",
                "jclaim.storage.type", "mongo",
                "jclaim.storage.mongo.database", "acme_db",
                "jclaim.storage.mongo.collection-name", "entities",
                "jclaim.storage.postgres.apply-schema", "false",
                "jclaim.match-sink.type", "logging",
                "jclaim.metrics.enabled", "false",
                "jclaim.health.enabled", "false"
        ));
        assertThat(props.urn().namespace()).isEqualTo("acme");
        assertThat(props.storage().type()).isEqualTo(JclaimProperties.StorageType.MONGO);
        assertThat(props.storage().mongo().database()).isEqualTo("acme_db");
        assertThat(props.storage().mongo().collectionName()).isEqualTo("entities");
        assertThat(props.storage().postgres().applySchema()).isFalse();
        assertThat(props.matchSink().type()).isEqualTo(JclaimProperties.MatchSinkType.LOGGING);
        assertThat(props.metrics().enabled()).isFalse();
        assertThat(props.health().enabled()).isFalse();
    }

    @Test
    void bindsUrnAndHumanIdProperties() {
        JclaimProperties p = bind(Map.of(
                "jclaim.urn.namespace", "acme",
                "jclaim.urn.type", "customer",
                "jclaim.human-id.template", "JG??????"));
        assertThat(p.urn().namespace()).isEqualTo("acme");
        assertThat(p.urn().type()).isEqualTo("customer");
        assertThat(p.humanId().template()).isEqualTo("JG??????");
    }

    @Test
    void bindsEntityTypesMap() {
        JclaimProperties props = bind(Map.of(
                "jclaim.urn.namespace", "acme",
                "jclaim.entity-types.customer.human-id.template", "CU-????-?",
                "jclaim.entity-types.customer.matching.spec", "matching/customer.yaml",
                "jclaim.entity-types.vehicle.storage.schema", "veh",
                "jclaim.entity-types.vehicle.storage.datasource", "vehicleDs"));
        assertThat(props.urn().namespace()).isEqualTo("acme");
        assertThat(props.entityTypes()).containsKeys("customer", "vehicle");
        assertThat(props.entityTypes().get("customer").humanId().template()).isEqualTo("CU-????-?");
        assertThat(props.entityTypes().get("customer").matching().spec()).isEqualTo("matching/customer.yaml");
        assertThat(props.entityTypes().get("vehicle").storage().schema()).isEqualTo("veh");
        assertThat(props.entityTypes().get("vehicle").storage().datasource()).isEqualTo("vehicleDs");
    }

    @Test
    void urnAndHumanIdDefaults() {
        JclaimProperties p = JclaimProperties.defaults();
        assertThat(p.urn().namespace()).isEqualTo("codery");
        assertThat(p.urn().type()).isEqualTo("entity");
        assertThat(p.humanId().template()).isNull();
    }

    @Test
    void bindsBlockingKeys() {
        JclaimProperties props = bind(Map.of(
                "jclaim.matching.spec", "matching/policy.yaml",
                "jclaim.matching.blocking-keys", "email,phone",
                "jclaim.entity-types.customer.matching.spec", "matching/customer.yaml",
                "jclaim.entity-types.customer.matching.blocking-keys[0]", "email"));
        assertThat(props.matching().blockingKeys()).containsExactly("email", "phone");
        assertThat(props.entityTypes().get("customer").matching().blockingKeys())
                .containsExactly("email");
    }

    @Test
    void blockingKeysDefaultsToEmpty() {
        JclaimProperties props = JclaimProperties.defaults();
        assertThat(props.matching().blockingKeys()).isEmpty();
    }

    /**
     * Blocking keys are per-type only and never inherited: a top-level
     * {@code jclaim.matching.blocking-keys} must not bleed into a per-type
     * entry that declared none. The per-type entry stays empty (blocking keys
     * travel with the per-type {@code matching.spec}), and the registrar's
     * {@code buildMatchingPolicy} reads only the per-type field — so the built
     * policy cannot inherit the top-level keys.
     */
    @Test
    void blockingKeysAreNotInheritedFromTopLevelToPerType() {
        JclaimProperties props = bind(Map.of(
                "jclaim.matching.blocking-keys", "email,phone",
                "jclaim.entity-types.customer.matching.spec", "matching/customer.yaml"));
        assertThat(props.matching().blockingKeys()).containsExactly("email", "phone");
        assertThat(props.entityTypes().get("customer").matching().blockingKeys()).isEmpty();
    }

    /**
     * The blocking-keys accessors return an immutable, never-null view on both
     * the single-type and per-type matching properties: a caller cannot mutate
     * the bound property post-boot, and a {@code null} field (e.g. a programmatic
     * {@code setBlockingKeys(null)}) yields an empty list rather than propagating
     * a low-signal NPE into the spec wiring.
     */
    @Test
    void blockingKeysAccessorsAreImmutableAndNullSafe() {
        JclaimProperties.Matching matching = new JclaimProperties.Matching();
        assertThatThrownBy(() -> matching.blockingKeys().add("injection"))
                .isInstanceOf(UnsupportedOperationException.class);
        matching.setBlockingKeys(null);
        assertThat(matching.blockingKeys()).isEmpty();

        JclaimProperties.EntityTypeMatching perType = new JclaimProperties.EntityTypeMatching();
        assertThatThrownBy(() -> perType.blockingKeys().add("injection"))
                .isInstanceOf(UnsupportedOperationException.class);
        perType.setBlockingKeys(null);
        assertThat(perType.blockingKeys()).isEmpty();
    }

    private static JclaimProperties bind(Map<String, String> map) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(map);
        return new Binder(source).bind("jclaim", JclaimProperties.class)
                .orElseGet(JclaimProperties::defaults);
    }
}
