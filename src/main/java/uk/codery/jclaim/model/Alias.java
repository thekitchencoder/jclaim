package uk.codery.jclaim.model;

import java.util.Objects;

/**
 * The {@code (source, sourceId)} pair recorded against a canonical entity.
 * Equality is structural — used as a map key in the storage layer's alias
 * index and as the canonical handle for inbound claims.
 */
public record Alias(SourceSystem source, String sourceId) {

    public Alias {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
    }

    /** Convenience factory. */
    public static Alias of(SourceSystem source, String sourceId) {
        return new Alias(source, sourceId);
    }

    /** Convenience factory taking a source-system name. */
    public static Alias of(String source, String sourceId) {
        return new Alias(new SourceSystem(source), sourceId);
    }
}
