package uk.codery.jclaim.id;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class HumanIdGeneratorTest {

    @Test
    void generate_isValidWhenChecked() {
        HumanIdGenerator gen = new HumanIdGenerator(new Random(42));
        for (int i = 0; i < 100; i++) {
            String id = gen.generate();
            assertThat(gen.isValid(id)).as(id).isTrue();
        }
    }

    @Test
    void generatesWithCustomFormat() {
        HumanIdFormat f = HumanIdFormat.ofTemplate("JG??????");
        HumanIdGenerator gen = new HumanIdGenerator(f, new java.util.Random(42));
        String id = gen.generate();
        assertThat(id).startsWith("JG").hasSize(8);
        assertThat(gen.isValid(id)).isTrue();
    }

    @Test
    void defaultGeneratorStillProducesLegacyShape() {
        HumanIdGenerator gen = new HumanIdGenerator(new java.util.Random(1));
        assertThat(gen.generate()).matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9]");
    }
}
