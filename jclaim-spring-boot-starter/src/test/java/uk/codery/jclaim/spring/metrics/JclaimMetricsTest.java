package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JclaimMetricsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JclaimAutoConfiguration.class,
                    JclaimMetricsAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void countsMatchedAndMintedSeparately() {
        runner.run(ctx -> {
            EntityResolver resolver = ctx.getBean(EntityResolver.class);
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);

            Claim claim = new Claim(
                    SourceSystem.of("crm"), "u-1",
                    List.of(MatchingAttribute.of("email", "a@x.example")));
            resolver.resolveOrMint(claim);
            resolver.resolveOrMint(claim);

            assertThat(registry.counter("jclaim.resolve", "outcome", "minted").count())
                    .isEqualTo(1.0);
            assertThat(registry.counter("jclaim.resolve", "outcome", "matched").count())
                    .isEqualTo(1.0);
        });
    }

    @Test
    void disabledByProperty() {
        runner.withPropertyValues("jclaim.metrics.enabled=false").run(ctx -> {
            assertThat(ctx.getBean(EntityResolver.class))
                    .isNotInstanceOf(MeteredEntityResolver.class);
        });
    }
}
