package uk.codery.jclaim.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static JclaimProperties bind(Map<String, String> map) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(map);
        return new Binder(source).bind("jclaim", JclaimProperties.class)
                .orElseGet(JclaimProperties::defaults);
    }
}
