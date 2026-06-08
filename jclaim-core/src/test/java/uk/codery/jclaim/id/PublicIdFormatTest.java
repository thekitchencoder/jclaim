package uk.codery.jclaim.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicIdFormatTest {

    @Test
    void defaultReproducesLegacyFormat() {
        // Golden: the old generator's format(0L) produced "0000-0000-0".
        assertThat(PublicIdFormat.DEFAULT.format(0L)).isEqualTo("0000-0000-0");
    }

    @Test
    void defaultIsEightDataCharsPlusCheck() {
        String id = PublicIdFormat.DEFAULT.format(0x0123456789L);
        assertThat(id).matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9]");
        assertThat(PublicIdFormat.DEFAULT.dataBits()).isEqualTo(40);
    }

    @Test
    void prefixTemplateRenders() {
        PublicIdFormat f = PublicIdFormat.ofTemplate("JG??????"); // JG + 5 data + check
        String id = f.format(0L);
        assertThat(id).startsWith("JG");
        assertThat(id).hasSize(8);           // 2 literal + 5 data + 1 check
        assertThat(f.dataBits()).isEqualTo(25);
    }

    @Test
    void literalHashAndShortDataRender() {
        PublicIdFormat f = PublicIdFormat.ofTemplate("#?????"); // '#' + 4 data + check
        assertThat(f.dataBits()).isEqualTo(20);
        assertThat(f.format(0L)).isEqualTo("#00000"); // '#' + "0000" + check '0'
    }

    @Test
    void rejectsTemplateWithFewerThanTwoPlaceholders() {
        assertThatThrownBy(() -> PublicIdFormat.ofTemplate("AB-?"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTemplateOverSixtyBitCeiling() {
        // 14 '?' => 13 data placeholders => 65 data bits => rejected (max 12 data).
        assertThatThrownBy(() -> PublicIdFormat.ofTemplate("?".repeat(14)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTwelveDataCharsAtCeiling() {
        PublicIdFormat f = PublicIdFormat.ofTemplate("?".repeat(13)); // 12 data + 1 check
        assertThat(f.dataBits()).isEqualTo(60);
    }

    @Test
    void acceptsSingleDataChar() {
        PublicIdFormat f = PublicIdFormat.ofTemplate("??"); // 1 data + 1 check
        assertThat(f.dataBits()).isEqualTo(5);
        assertThat(f.format(0L)).isEqualTo("00"); // data '0' + check '0' (Damm(0)=0)
    }

    @Test
    void validatesRoundTrip() {
        String id = PublicIdFormat.DEFAULT.format(0x9ABCDEF012L);
        assertThat(PublicIdFormat.DEFAULT.isValid(id)).isTrue();
    }

    @Test
    void forgivesCrockfordAliasesInDataAndCheck() {
        // "0000-0000-0": swap every '0' for letter 'O' (incl. the check digit) — still valid.
        String id = PublicIdFormat.DEFAULT.format(0L);
        String aliased = id.replace('0', 'O');
        assertThat(PublicIdFormat.DEFAULT.isValid(aliased)).isTrue();
    }

    @Test
    void rejectsWrongCheckDigit() {
        String id = PublicIdFormat.DEFAULT.format(0L);       // "0000-0000-0"
        String bad = id.substring(0, id.length() - 1) + "1"; // flip check 0 -> 1
        assertThat(PublicIdFormat.DEFAULT.isValid(bad)).isFalse();
    }

    @Test
    void rejectsLiteralMismatchAndWrongLength() {
        PublicIdFormat f = PublicIdFormat.ofTemplate("ID????-????-?");
        String id = f.format(0L);
        assertThat(f.isValid("XX" + id.substring(2))).isFalse(); // literal "ID" broken
        assertThat(f.isValid(id + "Z")).isFalse();               // too long
        assertThat(f.isValid(null)).isFalse();
    }

    @Test
    void rejectsNonDigitLetterInCheckPosition() {
        // 'A' decodes to 10 (> 9) — must fail the check-digit range guard.
        String id = PublicIdFormat.DEFAULT.format(0L);            // "0000-0000-0"
        String bad = id.substring(0, id.length() - 1) + "A";
        assertThat(PublicIdFormat.DEFAULT.isValid(bad)).isFalse();
    }

    @Test
    void rejectsInvalidSymbolInDataPosition() {
        // 'U' is dropped from the Crockford alphabet — must fail the data decode.
        PublicIdFormat.DEFAULT.format(0L); // anchor on default shape
        assertThat(PublicIdFormat.DEFAULT.isValid("000U-0000-0")).isFalse();
    }

    @Test
    void damm0and1RenderAsDigits() {
        assertThat(PublicIdFormat.DEFAULT.format(0L)).endsWith("0"); // Damm(0)=0
        long v = 0L;
        while (uk.codery.jclaim.id.Damm.checkDigit(v) != 1) v++;
        assertThat(PublicIdFormat.DEFAULT.format(v)).endsWith("1");
    }
}
