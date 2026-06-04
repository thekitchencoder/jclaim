package uk.codery.jclaim.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HumanIdFormatTest {

    @Test
    void defaultReproducesLegacyFormat() {
        // Golden: the old HumanIdGenerator.format(0L) produced "0000-0000-0".
        assertThat(HumanIdFormat.DEFAULT.format(0L)).isEqualTo("0000-0000-0");
    }

    @Test
    void defaultIsEightDataCharsPlusCheck() {
        String id = HumanIdFormat.DEFAULT.format(0x0123456789L);
        assertThat(id).matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9]");
        assertThat(HumanIdFormat.DEFAULT.dataBits()).isEqualTo(40);
    }

    @Test
    void prefixTemplateRenders() {
        HumanIdFormat f = HumanIdFormat.ofTemplate("JG??????"); // JG + 5 data + check
        String id = f.format(0L);
        assertThat(id).startsWith("JG");
        assertThat(id).hasSize(8);           // 2 literal + 5 data + 1 check
        assertThat(f.dataBits()).isEqualTo(25);
    }

    @Test
    void literalHashAndShortDataRender() {
        HumanIdFormat f = HumanIdFormat.ofTemplate("#?????"); // '#' + 4 data + check
        assertThat(f.dataBits()).isEqualTo(20);
        assertThat(f.format(0L)).isEqualTo("#00000"); // '#' + "0000" + check '0'
    }

    @Test
    void rejectsTemplateWithFewerThanTwoPlaceholders() {
        assertThatThrownBy(() -> HumanIdFormat.ofTemplate("AB-?"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTemplateOverSixtyBitCeiling() {
        // 14 '?' => 13 data placeholders => 65 data bits => rejected (max 12 data).
        assertThatThrownBy(() -> HumanIdFormat.ofTemplate("?".repeat(14)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTwelveDataCharsAtCeiling() {
        HumanIdFormat f = HumanIdFormat.ofTemplate("?".repeat(13)); // 12 data + 1 check
        assertThat(f.dataBits()).isEqualTo(60);
    }

    @Test
    void acceptsSingleDataChar() {
        HumanIdFormat f = HumanIdFormat.ofTemplate("??"); // 1 data + 1 check
        assertThat(f.dataBits()).isEqualTo(5);
        assertThat(f.format(0L)).isEqualTo("00"); // data '0' + check '0' (Damm(0)=0)
    }

    @Test
    void validatesRoundTrip() {
        String id = HumanIdFormat.DEFAULT.format(0x9ABCDEF012L);
        assertThat(HumanIdFormat.DEFAULT.isValid(id)).isTrue();
    }

    @Test
    void forgivesCrockfordAliasesInDataAndCheck() {
        // "0000-0000-0": swap every '0' for letter 'O' (incl. the check digit) — still valid.
        String id = HumanIdFormat.DEFAULT.format(0L);
        String aliased = id.replace('0', 'O');
        assertThat(HumanIdFormat.DEFAULT.isValid(aliased)).isTrue();
    }

    @Test
    void rejectsWrongCheckDigit() {
        String id = HumanIdFormat.DEFAULT.format(0L);       // "0000-0000-0"
        String bad = id.substring(0, id.length() - 1) + "1"; // flip check 0 -> 1
        assertThat(HumanIdFormat.DEFAULT.isValid(bad)).isFalse();
    }

    @Test
    void rejectsLiteralMismatchAndWrongLength() {
        HumanIdFormat f = HumanIdFormat.ofTemplate("ID????-????-?");
        String id = f.format(0L);
        assertThat(f.isValid("XX" + id.substring(2))).isFalse(); // literal "ID" broken
        assertThat(f.isValid(id + "Z")).isFalse();               // too long
        assertThat(f.isValid(null)).isFalse();
    }

    @Test
    void rejectsNonDigitLetterInCheckPosition() {
        // 'A' decodes to 10 (> 9) — must fail the check-digit range guard.
        String id = HumanIdFormat.DEFAULT.format(0L);            // "0000-0000-0"
        String bad = id.substring(0, id.length() - 1) + "A";
        assertThat(HumanIdFormat.DEFAULT.isValid(bad)).isFalse();
    }

    @Test
    void rejectsInvalidSymbolInDataPosition() {
        // 'U' is dropped from the Crockford alphabet — must fail the data decode.
        HumanIdFormat.DEFAULT.format(0L); // anchor on default shape
        assertThat(HumanIdFormat.DEFAULT.isValid("000U-0000-0")).isFalse();
    }

    @Test
    void damm0and1RenderAsDigits() {
        assertThat(HumanIdFormat.DEFAULT.format(0L)).endsWith("0"); // Damm(0)=0
        long v = 0L;
        while (uk.codery.jclaim.id.Damm.checkDigit(v) != 1) v++;
        assertThat(HumanIdFormat.DEFAULT.format(v)).endsWith("1");
    }
}
