package uk.codery.jclaim.model;

import java.util.Objects;

/**
 * Typed {@code (name, value)} attribute carried on {@link Claim claims} and
 * {@link Entity entities}. Names are caller-defined; values are arbitrary
 * objects compared with {@link Object#equals(Object)} when detecting
 * conflicts between stored entities and incoming claims.
 *
 * <p>The matching policy DSL (a later module) will consume these attributes
 * — hence the name. In this module they participate only in conflict
 * detection; the resolver matches on alias.
 */
public record MatchingAttribute(String name, Object value) {

    public MatchingAttribute {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("attribute name must not be blank");
        }
        Objects.requireNonNull(value, "value");
    }

    /** Convenience factory. */
    public static MatchingAttribute of(String name, Object value) {
        return new MatchingAttribute(name, value);
    }
}
