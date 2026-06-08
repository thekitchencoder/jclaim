package uk.codery.jclaim.id;

import java.util.Objects;

/**
 * Immutable public id format compiled from a template. In the template, '?' is a
 * placeholder: the LAST '?' renders the Damm check digit, every other '?'
 * renders a random data symbol drawn from the configured {@link IdAlphabet}; any
 * other character is a literal emitted verbatim. Every template char maps to
 * exactly one output char, so formatting and validation are fixed-width position
 * walks.
 *
 * <p>The data symbols form a base-{@code radix} number; the order-10 Damm check
 * digit is computed over that value and rendered via the alphabet's
 * {@link IdAlphabet#checkChar(int)}. For the default Crockford alphabet this
 * reproduces jclaim's historic {@code XXXX-XXXX-X} ID byte-for-byte.
 */
public final class PublicIdFormat {

    public static final PublicIdFormat DEFAULT = ofTemplate("????-????-?");

    private enum SlotType { LITERAL, DATA, CHECK }
    private record Slot(SlotType type, char literal) {}

    private final Slot[] plan;
    private final IdAlphabet alphabet;
    private final int dataChars;
    private final long maxValue;   // radix^dataChars; guaranteed < 2^63

    private PublicIdFormat(Slot[] plan, IdAlphabet alphabet, int dataChars, long maxValue) {
        this.plan = plan;
        this.alphabet = alphabet;
        this.dataChars = dataChars;
        this.maxValue = maxValue;
    }

    /** Compiles {@code template} over the default Crockford Base32 alphabet. */
    public static PublicIdFormat ofTemplate(String template) {
        return ofTemplate(template, CrockfordBase32.INSTANCE);
    }

    /** Compiles {@code template} over {@code alphabet}. */
    public static PublicIdFormat ofTemplate(String template, IdAlphabet alphabet) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(alphabet, "alphabet");
        int placeholders = 0;
        for (int i = 0; i < template.length(); i++) {
            if (template.charAt(i) == '?') placeholders++;
        }
        if (placeholders < 2) {
            throw new IllegalArgumentException(
                    "template needs >= 2 '?' (>=1 data + 1 check): '" + template + "'");
        }
        int dataChars = placeholders - 1;
        int maxDataChars = maxDataChars(alphabet.radix());
        if (dataChars > maxDataChars) {
            throw new IllegalArgumentException(
                    "template has " + dataChars + " data placeholders; max " + maxDataChars
                    + " for radix " + alphabet.radix() + ": '" + template + "'");
        }
        int lastPlaceholder = template.lastIndexOf('?');
        Slot[] plan = new Slot[template.length()];
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '?') {
                plan[i] = (i == lastPlaceholder)
                        ? new Slot(SlotType.CHECK, '?')
                        : new Slot(SlotType.DATA, '?');
            } else {
                plan[i] = new Slot(SlotType.LITERAL, c);
            }
        }
        return new PublicIdFormat(plan, alphabet, dataChars, pow(alphabet.radix(), dataChars));
    }

    /** Largest dataChars with {@code radix^dataChars < 2^63} (keeps maxValue a positive long). */
    private static int maxDataChars(int radix) {
        int n = 0;
        long v = 1L;
        while (v <= Long.MAX_VALUE / radix) {
            v *= radix;
            n++;
        }
        return n;
    }

    private static long pow(int radix, int exp) {
        long v = 1L;
        for (int i = 0; i < exp; i++) {
            v *= radix;
        }
        return v;
    }

    /** Number of random data symbols this template renders (excludes the check digit). */
    public int dataChars() {
        return dataChars;
    }

    /** True iff {@code candidate} fits this template and its Damm check digit holds. */
    public boolean isValid(String candidate) {
        if (candidate == null || candidate.length() != plan.length) {
            return false;
        }
        long acc = 0L;
        int check = -1;
        int radix = alphabet.radix();
        for (int i = 0; i < plan.length; i++) {
            char c = candidate.charAt(i);
            Slot slot = plan[i];
            switch (slot.type()) {
                case LITERAL -> { if (c != slot.literal()) return false; }
                case DATA -> {
                    int d = alphabet.decode(c);
                    if (d < 0 || d >= radix) return false;
                    acc = acc * radix + d;
                }
                case CHECK -> {
                    int d = alphabet.decodeCheck(c);
                    if (d < 0) return false;
                    check = d;
                }
            }
        }
        return Damm.verify(acc, check);
    }

    /** Formats {@code value} (reduced into range) per this template. */
    public String format(long value) {
        long v = Long.remainderUnsigned(value, maxValue);
        int radix = alphabet.radix();
        char[] data = new char[dataChars];
        long rem = v;
        for (int i = dataChars - 1; i >= 0; i--) {
            data[i] = alphabet.encode((int) (rem % radix));
            rem /= radix;
        }
        char checkChar = alphabet.checkChar(Damm.checkDigit(v));
        StringBuilder sb = new StringBuilder(plan.length);
        int d = 0;
        for (Slot slot : plan) {
            switch (slot.type()) {
                case LITERAL -> sb.append(slot.literal());
                case DATA -> sb.append(data[d++]);
                case CHECK -> sb.append(checkChar);
            }
        }
        return sb.toString();
    }
}
