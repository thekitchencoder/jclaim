package uk.codery.jclaim.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrockfordBase32Test {

    @Test
    void encode_zero_returnsAllZeroes() {
        assertThat(CrockfordBase32.encode(0L, 40)).isEqualTo("00000000");
    }

    @Test
    void encode_thenDecode_roundTripsFortyBitValues() {
        long[] samples = {0L, 1L, 31L, 32L, 1023L, 1024L, 0xDEADBEEFL, (1L << 40) - 1L};
        for (long v : samples) {
            String encoded = CrockfordBase32.encode(v, 40);
            assertThat(encoded).hasSize(8);
            assertThat(CrockfordBase32.decode(encoded)).as("value %d", v).isEqualTo(v);
        }
    }

    @Test
    void encode_usesUpperCaseAlphabetWithoutAmbiguousLetters() {
        for (int i = 0; i < CrockfordBase32.ALPHABET.length(); i++) {
            char c = CrockfordBase32.ALPHABET.charAt(i);
            assertThat(c).isNotIn('I', 'L', 'O', 'U');
        }
        assertThat(CrockfordBase32.ALPHABET).hasSize(32);
    }

    @Test
    void decode_acceptsCaseInsensitiveAndCrockfordAliases() {
        // 'o' aliases '0', 'i' and 'l' alias '1'
        assertThat(CrockfordBase32.decode("o")).isEqualTo(0L);
        assertThat(CrockfordBase32.decode("O")).isEqualTo(0L);
        assertThat(CrockfordBase32.decode("i")).isEqualTo(1L);
        assertThat(CrockfordBase32.decode("I")).isEqualTo(1L);
        assertThat(CrockfordBase32.decode("l")).isEqualTo(1L);
        assertThat(CrockfordBase32.decode("L")).isEqualTo(1L);
    }

    @Test
    void decode_ignoresHyphens() {
        long full = CrockfordBase32.decode("ABCDEFGH");
        long hyphenated = CrockfordBase32.decode("ABCD-EFGH");
        assertThat(hyphenated).isEqualTo(full);
    }

    @Test
    void decode_rejectsInvalidCharacters() {
        assertThatThrownBy(() -> CrockfordBase32.decode("@"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encode_rejectsNonMultipleOfFive() {
        assertThatThrownBy(() -> CrockfordBase32.encode(0L, 7))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalise_stripsHyphensAndUpperCases() {
        assertThat(CrockfordBase32.normalise("abcd-efgh")).isEqualTo("ABCDEFGH");
    }

    @Test
    void decodeCharHonoursAliasesAndRejectsInvalid() {
        assertThat(CrockfordBase32.decodeChar('0')).isZero();
        assertThat(CrockfordBase32.decodeChar('O')).isZero();   // alias O -> 0
        assertThat(CrockfordBase32.decodeChar('I')).isEqualTo(1); // alias I -> 1
        assertThat(CrockfordBase32.decodeChar('L')).isEqualTo(1); // alias L -> 1
        assertThat(CrockfordBase32.decodeChar('Z')).isEqualTo(31);
        assertThat(CrockfordBase32.decodeChar('U')).isEqualTo(-1); // dropped symbol
        assertThat(CrockfordBase32.decodeChar('-')).isEqualTo(-1);
        assertThat(CrockfordBase32.decodeChar('!')).isEqualTo(-1);
    }

    @Test
    void instance_behavesAsBase32IdAlphabet() {
        IdAlphabet a = CrockfordBase32.INSTANCE;
        assertThat(a.radix()).isEqualTo(32);
        assertThat(a.encode(0)).isEqualTo('0');
        assertThat(a.encode(10)).isEqualTo('A');
        assertThat(a.decode('A')).isEqualTo(10);
        assertThat(a.decode('U')).isEqualTo(-1);   // dropped letter
        assertThat(a.decode('o')).isEqualTo(0);    // documented alias o->0
        assertThat(a.checkChar(7)).isEqualTo('7');
        assertThat(a.decodeCheck('7')).isEqualTo(7);
        assertThat(a.decodeCheck('A')).isEqualTo(-1); // value 10 > 9, not a check digit
    }
}
