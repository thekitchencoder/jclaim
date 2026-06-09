package uk.codery.jclaim.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import uk.codery.jclaim.resolver.EntityResolver;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class PublicIdUnfilteredWarningTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class));

    @Test
    void warnsWhenTemplateConfiguredWithoutFilter(CapturedOutput output) {
        runner.withPropertyValues("jclaim.public-id.template=????-????-?")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EntityResolver.class);
                    assertThat(output).contains("minted unfiltered");
                    // The message must give a Spring user (who never touches the
                    // builder) an actionable path: a docs link covering the opt-out.
                    assertThat(output).contains(
                            "docs/adr/0003-public-id-acceptance-default-posture.md");
                });
    }

    @Test
    void silentWhenFilterOff(CapturedOutput output) {
        runner.withPropertyValues(
                        "jclaim.public-id.template=????-????-?",
                        "jclaim.public-id.filter=off")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EntityResolver.class);
                    assertThat(output).doesNotContain("minted unfiltered");
                });
    }

    @Test
    void silentWhenNoTemplate(CapturedOutput output) {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(EntityResolver.class);
            assertThat(output).doesNotContain("minted unfiltered");
        });
    }
}
