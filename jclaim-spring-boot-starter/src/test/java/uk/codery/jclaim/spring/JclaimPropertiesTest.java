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
        assertThat(props.namespace()).isEqualTo("codery");
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
                "jclaim.namespace", "acme",
                "jclaim.storage.type", "mongo",
                "jclaim.storage.mongo.database", "acme_db",
                "jclaim.storage.mongo.collection-name", "entities",
                "jclaim.storage.postgres.apply-schema", "false",
                "jclaim.match-sink.type", "logging",
                "jclaim.metrics.enabled", "false",
                "jclaim.health.enabled", "false"
        ));
        assertThat(props.namespace()).isEqualTo("acme");
        assertThat(props.storage().type()).isEqualTo(JclaimProperties.StorageType.MONGO);
        assertThat(props.storage().mongo().database()).isEqualTo("acme_db");
        assertThat(props.storage().mongo().collectionName()).isEqualTo("entities");
        assertThat(props.storage().postgres().applySchema()).isFalse();
        assertThat(props.matchSink().type()).isEqualTo(JclaimProperties.MatchSinkType.LOGGING);
        assertThat(props.metrics().enabled()).isFalse();
        assertThat(props.health().enabled()).isFalse();
    }

    private static JclaimProperties bind(Map<String, String> map) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(map);
        return new Binder(source).bind("jclaim", JclaimProperties.class)
                .orElseGet(JclaimProperties::defaults);
    }
}
