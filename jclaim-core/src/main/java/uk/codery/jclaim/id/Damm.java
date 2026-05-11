package uk.codery.jclaim.id;

/**
 * Damm's algorithm — classical order-10 totally anti-symmetric quasigroup.
 * Applied to the decimal representation of a {@code long} value to produce a
 * single decimal check digit. Detects all single-digit substitutions and all
 * adjacent transpositions, and unlike Luhn requires no special handling for
 * the position of the check digit.
 *
 * <p>The published quasigroup table is reproduced verbatim from Damm's 2004
 * dissertation. See
 * <a href="https://en.wikipedia.org/wiki/Damm_algorithm">https://en.wikipedia.org/wiki/Damm_algorithm</a>.
 *
 * <p>JClaim composes Damm with Crockford Base32 by computing the check digit
 * over the decimal representation of the 40-bit identifier value, then
 * encoding the 0–9 result back as a Crockford character (the alphabet's
 * leading ten symbols are the decimal digits, so the appended check digit
 * remains visually a digit). This deliberately uses the universally-verified
 * order-10 quasigroup rather than an ad-hoc order-32 extension.
 */
public final class Damm {

    private static final int[][] TABLE = {
            {0, 3, 1, 7, 5, 9, 8, 6, 4, 2},
            {7, 0, 9, 2, 1, 5, 4, 8, 6, 3},
            {4, 2, 0, 6, 8, 7, 1, 3, 5, 9},
            {1, 7, 5, 0, 9, 8, 3, 4, 2, 6},
            {6, 1, 2, 3, 0, 4, 5, 9, 7, 8},
            {3, 6, 7, 4, 2, 0, 9, 5, 8, 1},
            {5, 8, 6, 9, 7, 2, 0, 1, 3, 4},
            {8, 9, 4, 5, 3, 6, 2, 0, 1, 7},
            {9, 4, 3, 8, 6, 1, 7, 2, 0, 5},
            {2, 5, 8, 1, 4, 3, 6, 7, 9, 0},
    };

    private Damm() {
    }

    /**
     * Computes the Damm check digit (0–9) for the decimal digits of
     * {@code value}. The value must be non-negative.
     */
    public static int checkDigit(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative, was " + value);
        }
        return foldDecimal(value, 0);
    }

    /**
     * Verifies that {@code candidate} is the correct Damm check digit for
     * {@code value}'s decimal representation.
     */
    public static boolean verify(long value, int candidate) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative, was " + value);
        }
        if (candidate < 0 || candidate > 9) {
            return false;
        }
        return foldDecimal(value, 0) == candidate;
    }

    /**
     * Walks the decimal digits of {@code value} from most-significant to
     * least-significant, folding each into the Damm interim state.
     */
    private static int foldDecimal(long value, int interim) {
        if (value == 0L) {
            return TABLE[interim][0];
        }
        int[] digits = new int[20];
        int length = 0;
        long remaining = value;
        while (remaining > 0L) {
            digits[length++] = (int) (remaining % 10L);
            remaining /= 10L;
        }
        for (int i = length - 1; i >= 0; i--) {
            interim = TABLE[interim][digits[i]];
        }
        return interim;
    }
}
