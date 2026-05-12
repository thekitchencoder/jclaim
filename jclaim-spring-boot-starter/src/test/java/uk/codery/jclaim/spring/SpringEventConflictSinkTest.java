package uk.codery.jclaim.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.conflict.JclaimConflictEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        JclaimAutoConfiguration.class,
        SpringEventConflictSinkTest.RecorderConfig.class
})
class SpringEventConflictSinkTest {

    @Autowired EntityResolver resolver;
    @Autowired RecorderConfig.Recorder recorder;

    @Test
    void publishesJclaimConflictEventWhenAttributesDiverge() {
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
        JclaimConflictEvent event = recorder.events.get(0);
        assertThat(event.payload().differences()).hasSize(1);
        assertThat(event.payload().differences().get(0).name()).isEqualTo("email");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecorderConfig {
        @Bean Recorder recorder() { return new Recorder(); }

        static class Recorder {
            final List<JclaimConflictEvent> events = new CopyOnWriteArrayList<>();
            @EventListener void on(JclaimConflictEvent event) { events.add(event); }
        }
    }
}
