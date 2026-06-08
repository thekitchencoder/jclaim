package uk.codery.jclaim.id;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Mints the public identifier displayed alongside an entity's URN,
 * delegating shape and validation to a {@link PublicIdFormat}. The default
 * format is {@code ????-????-?} (eight Crockford Base32 data symbols + a Damm
 * check digit), reproducing jclaim's historic {@code XXXX-XXXX-X} ID.
 *
 * <p>The public ID is <strong>not</strong> derived from the entity URN — it is
 * an independently minted lookup attribute, stored alongside the entity.
 * Uniqueness is enforced by the storage adapter; on collision the resolver
 * re-mints.
 *
 * <p>This generator and its {@link PublicIdFormat} are immutable, so an instance
 * is thread-safe <em>iff</em> the injected entropy {@link Supplier} is. The
 * default {@link SecureRandom} is; a bare {@link Random} (used in tests) is not.
 */
public final class CrockfordPublicIdGenerator implements PublicIdGenerator {

    private final PublicIdFormat format;
    private final Supplier<Long> entropy;

    /** Default generator: legacy format, {@link SecureRandom} entropy. */
    public CrockfordPublicIdGenerator() {
        this(PublicIdFormat.DEFAULT);
    }

    /** Generator for {@code format}, backed by {@link SecureRandom}. */
    public CrockfordPublicIdGenerator(PublicIdFormat format) {
        this(format, new SecureRandom());
    }

    /** Legacy-format generator drawing bits from {@code random}. */
    public CrockfordPublicIdGenerator(Random random) {
        this(PublicIdFormat.DEFAULT, random);
    }

    /** Generator for {@code format} drawing bits from {@code random}. */
    public CrockfordPublicIdGenerator(PublicIdFormat format, Random random) {
        // Cast disambiguates the overload — bind to the (PublicIdFormat, Supplier) ctor.
        this(format, (Supplier<Long>) random::nextLong);
    }

    /** Legacy-format generator drawing raw entropy from {@code entropy}. */
    public CrockfordPublicIdGenerator(Supplier<Long> entropy) {
        this(PublicIdFormat.DEFAULT, entropy);
    }

    /** Generator for {@code format} drawing raw entropy from {@code entropy}. */
    public CrockfordPublicIdGenerator(PublicIdFormat format, Supplier<Long> entropy) {
        this.format = Objects.requireNonNull(format, "format");
        this.entropy = Objects.requireNonNull(entropy, "entropy");
    }

    /** Mints a fresh public ID. {@link PublicIdFormat#format(long)} masks the entropy. */
    @Override
    public String generate() {
        return format.format(entropy.get());
    }

    /** Validates {@code publicId} against this generator's format. */
    public boolean isValid(String publicId) {
        return format.isValid(publicId);
    }

    /** The format this generator mints. */
    public PublicIdFormat format() {
        return format;
    }
}
