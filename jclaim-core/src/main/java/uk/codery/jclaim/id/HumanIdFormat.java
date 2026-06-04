package uk.codery.jclaim.id;

import java.util.Objects;

/**
 * Immutable humanId format compiled from a template. In the template, '?' is a
 * placeholder: the LAST '?' renders the Damm check digit, every other '?'
 * renders a random Crockford Base32 data symbol; any other character is a
 * literal emitted verbatim. Every template char maps to exactly one output
 * char, so formatting and validation are fixed-width position walks.
 */
public final class HumanIdFormat {

    /** 60-bit ceiling keeps the value in a long. */
    private static final int MAX_DATA_CHARS = 12;

    public static final HumanIdFormat DEFAULT = ofTemplate("????-????-?");

    private enum SlotType { LITERAL, DATA, CHECK }
    private record Slot(SlotType type, char literal) {}

    private final Slot[] plan;
    private final int dataBits;
    private final long mask;

    private HumanIdFormat(Slot[] plan, int dataBits) {
        this.plan = plan;
        this.dataBits = dataBits;
        // Safe only because MAX_DATA_CHARS caps dataBits at 60; 1L << 65 would wrap
        // (Java shift distance is mod 64). Raising the cap requires BigInteger, not a bigger shift.
        this.mask = (1L << dataBits) - 1L;
    }

    public static HumanIdFormat ofTemplate(String template) {
        Objects.requireNonNull(template, "template");
        int placeholders = 0;
        for (int i = 0; i < template.length(); i++) {
            if (template.charAt(i) == '?') placeholders++;
        }
        if (placeholders < 2) {
            throw new IllegalArgumentException(
                    "template needs >= 2 '?' (>=1 data + 1 check): '" + template + "'");
        }
        int dataChars = placeholders - 1;
        if (dataChars > MAX_DATA_CHARS) {
            throw new IllegalArgumentException(
                    "template has " + dataChars + " data placeholders; max " + MAX_DATA_CHARS
                    + " (60-bit ceiling): '" + template + "'");
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
        int dataBits = dataChars * 5;
        return new HumanIdFormat(plan, dataBits);
    }

    public int dataBits() {
        return dataBits;
    }

    /** True iff {@code candidate} fits this template and its Damm check digit holds. */
    public boolean isValid(String candidate) {
        if (candidate == null || candidate.length() != plan.length) {
            return false;
        }
        long acc = 0L;
        int check = -1;
        for (int i = 0; i < plan.length; i++) {
            char c = candidate.charAt(i);
            Slot slot = plan[i];
            switch (slot.type()) {
                case LITERAL -> { if (c != slot.literal()) return false; }
                case DATA -> {
                    int d = decodeSymbol(c);
                    if (d < 0) return false;
                    acc = (acc << 5) | d;
                }
                case CHECK -> {
                    int d = decodeSymbol(c);
                    if (d < 0 || d > 9) return false;
                    check = d;
                }
            }
        }
        return Damm.verify(acc, check);
    }

    /** Decodes one Crockford symbol (honouring O/I/L aliases); -1 if invalid. */
    private static int decodeSymbol(char c) {
        try {
            return (int) CrockfordBase32.decode(String.valueOf(c));
        } catch (IllegalArgumentException ex) {
            return -1;
        }
    }

    /** Formats the low {@code dataBits} of {@code value} per this template. */
    public String format(long value) {
        long v = value & mask;
        String data = CrockfordBase32.encode(v, dataBits);
        char checkChar = CrockfordBase32.ALPHABET.charAt(Damm.checkDigit(v));
        StringBuilder sb = new StringBuilder(plan.length);
        int d = 0;
        for (Slot slot : plan) {
            switch (slot.type()) {
                case LITERAL -> sb.append(slot.literal());
                case DATA -> sb.append(data.charAt(d++));
                case CHECK -> sb.append(checkChar);
            }
        }
        return sb.toString();
    }
}
