package uk.codery.jclaim.id;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Mints the human-friendly identifier displayed alongside an entity's URN,
 * delegating shape and validation to a {@link HumanIdFormat}. The default
 * format is {@code ????-????-?} (eight Crockford Base32 data symbols + a Damm
 * check digit), reproducing jclaim's historic {@code XXXX-XXXX-X} ID.
 *
 * <p>The human ID is <strong>not</strong> derived from the entity URN — it is
 * an independently minted lookup attribute, stored alongside the entity.
 * Uniqueness is enforced by the storage adapter; on collision the resolver
 * re-mints.
 */
public final class HumanIdGenerator {

    private final HumanIdFormat format;
    private final Supplier<Long> entropy;

    /** Default generator: legacy format, {@link SecureRandom} entropy. */
    public HumanIdGenerator() {
        this(HumanIdFormat.DEFAULT);
    }

    /** Generator for {@code format}, backed by {@link SecureRandom}. */
    public HumanIdGenerator(HumanIdFormat format) {
        this(format, new SecureRandom());
    }

    /** Legacy-format generator drawing bits from {@code random}. */
    public HumanIdGenerator(Random random) {
        this(HumanIdFormat.DEFAULT, random);
    }

    /** Generator for {@code format} drawing bits from {@code random}. */
    public HumanIdGenerator(HumanIdFormat format, Random random) {
        this(format, (Supplier<Long>) random::nextLong);
    }

    /** Legacy-format generator drawing raw entropy from {@code entropy}. */
    public HumanIdGenerator(Supplier<Long> entropy) {
        this(HumanIdFormat.DEFAULT, entropy);
    }

    /** Generator for {@code format} drawing raw entropy from {@code entropy}. */
    public HumanIdGenerator(HumanIdFormat format, Supplier<Long> entropy) {
        this.format = Objects.requireNonNull(format, "format");
        this.entropy = Objects.requireNonNull(entropy, "entropy");
    }

    /** Mints a fresh human ID. {@link HumanIdFormat#format} masks the entropy. */
    public String generate() {
        return format.format(entropy.get());
    }

    /** Validates {@code humanId} against this generator's format. */
    public boolean isValid(String humanId) {
        return format.isValid(humanId);
    }

    /** The format this generator mints. */
    public HumanIdFormat format() {
        return format;
    }
}
