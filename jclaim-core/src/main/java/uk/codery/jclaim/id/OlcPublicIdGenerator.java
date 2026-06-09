package uk.codery.jclaim.id;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Mints a vowel-resistant public identifier over Open Location Code's curated
 * 20-symbol alphabet (see {@link OlcAlphabet}). A sibling to
 * {@link CrockfordPublicIdGenerator}; both implement {@link PublicIdGenerator}.
 * The default format is {@code ????-????-?} (eight OLC data symbols plus a
 * decimal Damm check digit).
 *
 * <p>All base-20 behaviour lives in the {@link PublicIdFormat} + {@link OlcAlphabet};
 * this generator only supplies entropy. Immutable and thread-safe <em>iff</em>
 * the injected entropy {@link Supplier} is — the default {@link SecureRandom} is;
 * a bare {@link Random} (used in tests) is not.
 */
public final class OlcPublicIdGenerator implements PublicIdGenerator {

    /** Default OLC shape: eight data symbols plus a decimal check digit. */
    private static final PublicIdFormat DEFAULT_FORMAT =
            PublicIdFormat.ofTemplate("????-????-?", OlcAlphabet.INSTANCE);

    private final PublicIdFormat format;
    private final Supplier<Long> entropy;

    /** Default OLC generator: {@code ????-????-?} shape, {@link SecureRandom} entropy. */
    public OlcPublicIdGenerator() {
        this(DEFAULT_FORMAT);
    }

    /** Generator for {@code format}, backed by {@link SecureRandom}. */
    public OlcPublicIdGenerator(PublicIdFormat format) {
        this(format, new SecureRandom());
    }

    /** Default-shape OLC generator drawing bits from {@code random}. */
    public OlcPublicIdGenerator(Random random) {
        this(DEFAULT_FORMAT, random);
    }

    /** Generator for {@code format} drawing bits from {@code random}. */
    public OlcPublicIdGenerator(PublicIdFormat format, Random random) {
        // requireNonNull guards explicitly (and with a message) rather than relying on the
        // method-ref receiver NPE — keeps the null contract fail-fast if this is ever
        // refactored to a lambda. Cast disambiguates the (PublicIdFormat, Supplier) overload.
        this(format, (Supplier<Long>) Objects.requireNonNull(random, "random")::nextLong);
    }

    /** Generator for {@code format} drawing raw entropy from {@code entropy}. */
    public OlcPublicIdGenerator(PublicIdFormat format, Supplier<Long> entropy) {
        this.format = Objects.requireNonNull(format, "format");
        this.entropy = Objects.requireNonNull(entropy, "entropy");
    }

    /** Mints a fresh public ID. {@link PublicIdFormat#format(long)} renders the entropy. */
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
