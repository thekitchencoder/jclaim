package uk.codery.jclaim.model;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Canonical entity identifier expressed as a URN of the form
 * {@code urn:<namespace>:entity:<UUID v7>}. The namespace is caller-defined
 * (defaulted to {@code codery} by {@link #DEFAULT_NAMESPACE}); the UUID is
 * always RFC 9562 version 7.
 *
 * <p>Equality and hashing follow record semantics over the canonical URN
 * string. Use {@link #uuid()} or {@link #namespace()} when the components
 * are needed individually.
 */
public record EntityId(String urn) {

    public static final String DEFAULT_NAMESPACE = "codery";

    private static final Pattern URN_PATTERN = Pattern.compile(
            "^urn:([A-Za-z0-9][A-Za-z0-9-]*):entity:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$"
    );

    public EntityId {
        Objects.requireNonNull(urn, "urn");
        if (!URN_PATTERN.matcher(urn).matches()) {
            throw new IllegalArgumentException("Invalid entity URN: '" + urn + "'");
        }
    }

    /** Builds an entity ID from a namespace and UUID. */
    public static EntityId of(String namespace, UUID uuid) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(uuid, "uuid");
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        return new EntityId("urn:" + namespace + ":entity:" + uuid);
    }

    /** Builds an entity ID under the default {@code codery} namespace. */
    public static EntityId of(UUID uuid) {
        return of(DEFAULT_NAMESPACE, uuid);
    }

    /** Returns the namespace component. */
    public String namespace() {
        return URN_PATTERN.matcher(urn).results()
                .findFirst()
                .map(m -> m.group(1))
                .orElseThrow(() -> new IllegalStateException("Validated URN failed re-parse: " + urn));
    }

    /** Returns the UUID component. */
    public UUID uuid() {
        return URN_PATTERN.matcher(urn).results()
                .findFirst()
                .map(m -> UUID.fromString(m.group(2)))
                .orElseThrow(() -> new IllegalStateException("Validated URN failed re-parse: " + urn));
    }

    @Override
    public String toString() {
        return urn;
    }
}
