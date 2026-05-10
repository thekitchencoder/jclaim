package uk.codery.jclaim.id;

import java.security.SecureRandom;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Generates and validates the human-friendly identifier displayed alongside
 * an entity's URN. The format is {@code XXXX-XXXX-X}: eight randomly drawn
 * Crockford Base32 symbols (40 bits of entropy) followed by a Damm check
 * digit computed over the decimal representation of the 40-bit value, encoded
 * back into the Crockford alphabet. Hyphens are presentational only.
 *
 * <p>The human ID is <strong>not</strong> derived from the entity URN — it is
 * an independently minted lookup attribute, stored alongside the entity.
 * Uniqueness is enforced by the storage adapter; on collision the resolver
 * re-mints.
 *
 * <p>Inputs are normalised through {@link CrockfordBase32}, so users typing
 * {@code o} for {@code 0} or {@code l} for {@code 1} verify correctly.
 */
public final class HumanIdGenerator {

    private static final int DATA_BITS = 40;
    private static final int DATA_CHARS = DATA_BITS / 5;     // 8
    private static final long MASK = (1L << DATA_BITS) - 1L; // 40-bit mask

    private final Supplier<Long> entropy;

    /** Default generator backed by {@link SecureRandom}. */
    public HumanIdGenerator() {
        this(new SecureRandom());
    }

    /** Test-friendly constructor that draws bits from the supplied {@link Random}. */
    public HumanIdGenerator(Random random) {
        this(() -> random.nextLong() & MASK);
    }

    /** Constructor taking a raw 40-bit entropy supplier. */
    public HumanIdGenerator(Supplier<Long> entropy) {
        this.entropy = entropy;
    }

    /** Mints a fresh human ID. */
    public String generate() {
        long value = entropy.get() & MASK;
        return format(value);
    }

    /** Validates that {@code humanId} is well-formed and its Damm check digit is correct. */
    public static boolean isValid(String humanId) {
        if (humanId == null) {
            return false;
        }
        String stripped;
        try {
            stripped = CrockfordBase32.normalise(humanId);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (stripped.length() != DATA_CHARS + 1) {
            return false;
        }
        long value = CrockfordBase32.decode(stripped.substring(0, DATA_CHARS));
        int check;
        try {
            check = (int) CrockfordBase32.decode(stripped.substring(DATA_CHARS));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return check >= 0 && check <= 9 && Damm.verify(value, check);
    }

    /**
     * Formats a raw 40-bit value as a human ID string. Intended for tests and
     * for callers that mint values from a deterministic source.
     */
    public static String format(long value) {
        if ((value & ~MASK) != 0L) {
            throw new IllegalArgumentException("value exceeds " + DATA_BITS + " bits: " + value);
        }
        String data = CrockfordBase32.encode(value, DATA_BITS);
        int check = Damm.checkDigit(value);
        char checkChar = CrockfordBase32.ALPHABET.charAt(check);
        return data.substring(0, 4) + "-" + data.substring(4, 8) + "-" + checkChar;
    }
}
