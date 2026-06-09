package uk.codery.jclaim.resolver;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.id.CrockfordPublicIdGenerator;
import uk.codery.jclaim.id.FilteringPublicIdGenerator;
import uk.codery.jclaim.id.PublicIdFormat;
import uk.codery.jclaim.id.PublicIdGenerator;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import static org.assertj.core.api.Assertions.assertThat;

class UnfilteredPublicIdWarningTest {

    private static FilteringPublicIdGenerator defaultTemplateGenerator() {
        return new FilteringPublicIdGenerator(
                new CrockfordPublicIdGenerator(PublicIdFormat.ofTemplate("????-????-?")),
                FilteringPublicIdGenerator.ALLOW_ALL);
    }

    @Test
    void warns_whenDefaultAllowAllFilter_andNotAcknowledged() {
        assertThat(DefaultEntityResolver.shouldWarnUnfiltered(
                defaultTemplateGenerator(), false)).isTrue();
    }

    @Test
    void silent_whenAcknowledged() {
        assertThat(DefaultEntityResolver.shouldWarnUnfiltered(
                defaultTemplateGenerator(), true)).isFalse();
    }

    @Test
    void silent_whenRealFilterPredicate() {
        FilteringPublicIdGenerator filtered = new FilteringPublicIdGenerator(
                new CrockfordPublicIdGenerator(PublicIdFormat.ofTemplate("????-????-?")),
                s -> !s.contains("0"));
        assertThat(DefaultEntityResolver.shouldWarnUnfiltered(filtered, false)).isFalse();
    }

    @Test
    void silent_whenNoGenerator() {
        assertThat(DefaultEntityResolver.shouldWarnUnfiltered(null, false)).isFalse();
    }

    @Test
    void silent_whenRawGeneratorNotDecorated() {
        // Direct generator via the advanced publicIdGenerator(...) hook is not a
        // FilteringPublicIdGenerator, so it is out of scope for the nudge.
        PublicIdGenerator raw =
                new CrockfordPublicIdGenerator(PublicIdFormat.ofTemplate("????-????-?"));
        assertThat(DefaultEntityResolver.shouldWarnUnfiltered(raw, false)).isFalse();
    }

    @Test
    void allowUnfilteredPublicIds_buildsWorkingResolver() {
        DefaultEntityResolver resolver = DefaultEntityResolver.builder(new InMemoryEntityStorage())
                .publicIdTemplate("????-????-?")
                .allowUnfilteredPublicIds()
                .build();
        assertThat(resolver).isNotNull();
    }
}
