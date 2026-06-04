package uk.codery.jclaim.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.match.JclaimMatchEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        JclaimAutoConfiguration.class,
        SpringEventMatchSinkTest.RecorderConfig.class
})
class SpringEventMatchSinkTest {

    @Autowired EntityResolver resolver;
    @Autowired RecorderConfig.Recorder recorder;

    @Test
    void publishesJclaimMatchEventWhenAttributesDiverge() {
        Claim first = new Claim(
                SourceSystem.of("crm"),
                "user-1",
                List.of(MatchingAttribute.of("email", "alice@example.com")));
        resolver.resolveOrMint(first);

        Claim conflicting = new Claim(
                SourceSystem.of("crm"),
                "user-1",
                List.of(MatchingAttribute.of("email", "alice+new@example.com")));
        resolver.resolveOrMint(conflicting);

        assertThat(recorder.events).hasSize(1);
        JclaimMatchEvent event = recorder.events.get(0);
        assertThat(event.payload()).isInstanceOf(EntityAttributesConflicted.class);
        EntityAttributesConflicted payload = (EntityAttributesConflicted) event.payload();
        assertThat(payload.differingValues()).hasSize(1);
        assertThat(payload.differingValues().get(0).name()).isEqualTo("email");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecorderConfig {
        @Bean Recorder recorder() { return new Recorder(); }

        static class Recorder {
            final List<JclaimMatchEvent> events = new CopyOnWriteArrayList<>();
            @EventListener void on(JclaimMatchEvent event) { events.add(event); }
        }
    }
}
