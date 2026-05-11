package uk.codery.jclaim.id;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class HumanIdGeneratorTest {

    @Test
    void format_zeroValue_producesAllZeroesWithDammZeroCheck() {
        // 40 zero bits → 8 '0's; Damm("0") = 0 → check char '0'
        assertThat(HumanIdGenerator.format(0L)).isEqualTo("0000-0000-0");
    }

    @Test
    void format_producesXxxxXxxxXShape() {
        String id = HumanIdGenerator.format(0xCAFEBABEL & 0xFFFFFFFFFFL);
        assertThat(id).matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]");
    }

    @Test
    void generate_isValidWhenChecked() {
        HumanIdGenerator gen = new HumanIdGenerator(new Random(42));
        for (int i = 0; i < 100; i++) {
            String id = gen.generate();
            assertThat(HumanIdGenerator.isValid(id)).as(id).isTrue();
        }
    }

    @Test
    void isValid_rejectsCorruptedCheckDigit() {
        String good = HumanIdGenerator.format(123_456_789L);
        char checkChar = good.charAt(good.length() - 1);
        char swapped = (char) (checkChar == '0' ? '1' : '0');
        String bad = good.substring(0, good.length() - 1) + swapped;
        assertThat(HumanIdGenerator.isValid(bad)).isFalse();
    }

    @Test
    void isValid_acceptsCrockfordAliases() {
        // Lower-case input + 'O' alias for '0' should still verify.
        String good = HumanIdGenerator.format(0L);
        String userTyped = good.toLowerCase().replace('0', 'o');
        assertThat(HumanIdGenerator.isValid(userTyped)).isTrue();
    }

    @Test
    void isValid_rejectsMalformedInput() {
        assertThat(HumanIdGenerator.isValid(null)).isFalse();
        assertThat(HumanIdGenerator.isValid("")).isFalse();
        assertThat(HumanIdGenerator.isValid("not a humanid")).isFalse();
        assertThat(HumanIdGenerator.isValid("AAAA-BBBB")).isFalse(); // too short
    }
}
