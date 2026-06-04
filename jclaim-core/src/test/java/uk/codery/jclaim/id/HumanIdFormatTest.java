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
}
