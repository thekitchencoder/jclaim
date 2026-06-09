package uk.codery.jclaim.id;

import java.util.Arrays;

/**
 * Open Location Code's 20-symbol alphabet ({@code 23456789CFGHJMPQRVWX}),
 * selected to avoid spelling words: it excludes every vowel and the digits
 * {@code 0}/{@code 1}. Used for vowel-resistant public IDs.
 *
 * <p>Data symbols form a base-20 value. The order-10 Damm check digit is rendered
 * as a literal decimal character ({@code '0'}–{@code '9'}) in the check position —
 * the one position where a {@code 0}/{@code 1} may appear; the data symbols stay
 * pure OLC. Decoding is case-insensitive; there are no ambiguous-pair aliases.
 *
 * <p>See <a href="https://github.com/google/open-location-code">Open Location Code</a>.
 */
public final class OlcAlphabet implements IdAlphabet {

    /** The canonical OLC code alphabet, 20 symbols. */
    public static final String ALPHABET = "23456789CFGHJMPQRVWX";

    /** Singleton instance. */
    public static final IdAlphabet INSTANCE = new OlcAlphabet();

    private static final int[] DECODE = new int[128];

    static {
        Arrays.fill(DECODE, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            char c = ALPHABET.charAt(i);
            DECODE[c] = i;
            DECODE[Character.toLowerCase(c)] = i;
        }
    }

    private OlcAlphabet() {
    }

    @Override
    public int radix() {
        return 20;
    }

    @Override
    public char encode(int index) {
        return ALPHABET.charAt(index);
    }

    @Override
    public int decode(char c) {
        return c < DECODE.length ? DECODE[c] : -1;
    }

    @Override
    public char checkChar(int digit) {
        return (char) ('0' + digit);
    }

    @Override
    public int decodeCheck(char c) {
        return (c >= '0' && c <= '9') ? c - '0' : -1;
    }
}
