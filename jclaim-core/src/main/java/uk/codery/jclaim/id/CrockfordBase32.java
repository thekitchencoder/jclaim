package uk.codery.jclaim.id;

/**
 * Crockford Base32 alphabet — 32 symbols with the ambiguous letters
 * {@code I}, {@code L}, {@code O} and {@code U} dropped. Encoded output is
 * upper-case; decoding accepts both cases and forgives the documented
 * substitutions ({@code i}/{@code l} → {@code 1}, {@code o} → {@code 0}).
 *
 * <p>See <a href="https://www.crockford.com/base32.html">https://www.crockford.com/base32.html</a>
 * for the reference specification.
 */
public final class CrockfordBase32 implements IdAlphabet {

    /** Singleton {@link IdAlphabet} view of this base-32 codec. */
    public static final IdAlphabet INSTANCE = new CrockfordBase32();

    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static final int[] DECODE = new int[128];

    static {
        for (int i = 0; i < DECODE.length; i++) {
            DECODE[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length(); i++) {
            char c = ALPHABET.charAt(i);
            DECODE[c] = i;
            DECODE[Character.toLowerCase(c)] = i;
        }
        // Crockford's documented case-insensitive aliases.
        DECODE['o'] = 0;
        DECODE['O'] = 0;
        DECODE['i'] = 1;
        DECODE['I'] = 1;
        DECODE['l'] = 1;
        DECODE['L'] = 1;
    }

    private CrockfordBase32() {
    }

    @Override
    public int radix() {
        return 32;
    }

    @Override
    public char encode(int index) {
        return ALPHABET.charAt(index);
    }

    @Override
    public int decode(char c) {
        return decodeChar(c);
    }

    @Override
    public char checkChar(int digit) {
        return ALPHABET.charAt(digit);
    }

    @Override
    public int decodeCheck(char c) {
        int d = decodeChar(c);
        return (d >= 0 && d <= 9) ? d : -1;
    }

    /**
     * Decodes a single character to its 0–31 Crockford value, honouring the
     * documented O/I/L aliases; returns -1 for any non-Crockford character.
     * Allocation-free and exception-free — preferred over {@link #decode(String)}
     * for single-character validation on hot paths.
     */
    public static int decodeChar(char c) {
        return c < DECODE.length && DECODE[c] >= 0 ? DECODE[c] : -1;
    }

    /** Encodes the lower {@code bits} of {@code value} as Crockford Base32. */
    public static String encode(long value, int bits) {
        if (bits <= 0 || bits > 64) {
            throw new IllegalArgumentException("bits must be in 1..64, was " + bits);
        }
        if (bits % 5 != 0) {
            throw new IllegalArgumentException("bits must be a multiple of 5, was " + bits);
        }
        int chars = bits / 5;
        char[] out = new char[chars];
        long shifting = value;
        for (int i = chars - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (shifting & 0x1F));
            shifting >>>= 5;
        }
        return new String(out);
    }

    /**
     * Decodes a Crockford Base32 string, ignoring hyphens. Accepts the
     * documented case-insensitive substitutions. Throws
     * {@link IllegalArgumentException} on any other character.
     */
    public static long decode(String encoded) {
        long acc = 0L;
        int digits = 0;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '-') {
                continue;
            }
            if (c >= DECODE.length || DECODE[c] < 0) {
                throw new IllegalArgumentException("Invalid Crockford Base32 character: '" + c + "'");
            }
            digits++;
            if (digits > 13) {
                throw new IllegalArgumentException("Input exceeds 64 bits when decoded");
            }
            acc = (acc << 5) | DECODE[c];
        }
        return acc;
    }

    /** Normalises a Crockford Base32 string to canonical upper-case form, stripping hyphens. */
    public static String normalise(String encoded) {
        StringBuilder sb = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '-') {
                continue;
            }
            if (c >= DECODE.length || DECODE[c] < 0) {
                throw new IllegalArgumentException("Invalid Crockford Base32 character: '" + c + "'");
            }
            sb.append(ALPHABET.charAt(DECODE[c]));
        }
        return sb.toString();
    }
}
