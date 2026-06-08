package uk.codery.jclaim.id;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class CrockfordPublicIdGeneratorTest {

    @Test
    void generate_isValidWhenChecked() {
        CrockfordPublicIdGenerator gen = new CrockfordPublicIdGenerator(new Random(42));
        for (int i = 0; i < 100; i++) {
            String id = gen.generate();
            assertThat(gen.isValid(id)).as(id).isTrue();
        }
    }

    @Test
    void generatesWithCustomFormat() {
        PublicIdFormat f = PublicIdFormat.ofTemplate("JG??????");
        CrockfordPublicIdGenerator gen = new CrockfordPublicIdGenerator(f, new java.util.Random(42));
        String id = gen.generate();
        assertThat(id).startsWith("JG").hasSize(8);
        assertThat(gen.isValid(id)).isTrue();
    }

    @Test
    void defaultGeneratorStillProducesLegacyShape() {
        CrockfordPublicIdGenerator gen = new CrockfordPublicIdGenerator(new java.util.Random(1));
        assertThat(gen.generate()).matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9]");
    }

    @Test
    void noArgConstructor_usesSecureRandomAndDefaultFormat() {
        CrockfordPublicIdGenerator gen = new CrockfordPublicIdGenerator();
        String id = gen.generate();
        assertThat(id).matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9]");
        assertThat(gen.isValid(id)).as(id).isTrue();
    }

    @Test
    void supplierConstructor_usesDefaultFormatOverEntropy() {
        CrockfordPublicIdGenerator gen = new CrockfordPublicIdGenerator((Supplier<Long>) () -> 0L);
        assertThat(gen.generate()).isEqualTo("0000-0000-0");
    }

    @Test
    void format_exposesTheConfiguredFormat() {
        PublicIdFormat f = PublicIdFormat.ofTemplate("JG??????");
        CrockfordPublicIdGenerator gen = new CrockfordPublicIdGenerator(f, new Random(42));
        assertThat(gen.format()).isSameAs(f);
    }
}
