package uk.codery.jclaim.model;

import java.util.Objects;

/**
 * Named origin of identity claims (e.g. {@code "ecommerce"}, {@code "pos"},
 * {@code "crm"}). Source-system names are caller-defined; JClaim only
 * requires that the name be non-blank.
 */
public record SourceSystem(String name) {

    public SourceSystem {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("SourceSystem name must not be blank");
        }
    }

    /** Convenience factory. */
    public static SourceSystem of(String name) {
        return new SourceSystem(name);
    }
}
