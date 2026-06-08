package uk.codery.jclaim.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OlcAlphabetTest {

    private final IdAlphabet olc = OlcAlphabet.INSTANCE;

    @Test
    void radixIsTwenty() {
        assertThat(olc.radix()).isEqualTo(20);
    }

    @Test
    void encodeDecodeRoundTripsAllSymbols() {
        for (int i = 0; i < 20; i++) {
            char symbol = olc.encode(i);
            assertThat(olc.decode(symbol)).as("index %d", i).isEqualTo(i);
        }
    }

    @Test
    void firstAndLastSymbolsAreOlcCanonical() {
        assertThat(olc.encode(0)).isEqualTo('2');   // OLC alphabet starts at '2'
        assertThat(olc.encode(19)).isEqualTo('X');  // ...ends at 'X'
    }

    @Test
    void decodeRejectsNonOlcChars() {
        assertThat(olc.decode('0')).isEqualTo(-1);  // no 0
        assertThat(olc.decode('1')).isEqualTo(-1);  // no 1
        assertThat(olc.decode('A')).isEqualTo(-1);  // vowel
        assertThat(olc.decode('E')).isEqualTo(-1);  // vowel
        assertThat(olc.decode('-')).isEqualTo(-1);  // separator
    }

    @Test
    void decodeIsCaseInsensitive() {
        assertThat(olc.decode('c')).isEqualTo(olc.decode('C'));
    }

    @Test
    void checkCharRendersDecimalDigit() {
        for (int d = 0; d <= 9; d++) {
            assertThat(olc.checkChar(d)).isEqualTo((char) ('0' + d));
        }
    }

    @Test
    void decodeCheckAcceptsDigitsRejectsLetters() {
        assertThat(olc.decodeCheck('0')).isEqualTo(0);
        assertThat(olc.decodeCheck('9')).isEqualTo(9);
        assertThat(olc.decodeCheck('C')).isEqualTo(-1);
        assertThat(olc.decodeCheck('X')).isEqualTo(-1);
    }

    @Test
    void decodeRejectsCharsAbove127() {
        assertThat(olc.decode((char) 200)).isEqualTo(-1);
    }

    @Test
    void decodeCheckRejectsCharsBelowDigitRange() {
        assertThat(olc.decodeCheck('-')).isEqualTo(-1);  // '-' (45) is below '0' (48)
        assertThat(olc.decodeCheck('!')).isEqualTo(-1);
    }
}
