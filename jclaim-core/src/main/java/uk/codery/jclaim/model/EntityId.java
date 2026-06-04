package uk.codery.jclaim.model;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Canonical entity identifier expressed as a URN of the form
 * {@code urn:<namespace>:<type>:<UUID v7>}. The namespace and type segments
 * are both caller-defined (defaulted to {@code codery} by
 * {@link #DEFAULT_NAMESPACE} and {@code entity} by {@link #DEFAULT_TYPE});
 * the UUID is always RFC 9562 version 7.
 *
 * <p>Equality and hashing follow record semantics over the canonical URN
 * string. Use {@link #uuid()}, {@link #namespace()} or {@link #type()} when
 * the components are needed individually.
 */
public record EntityId(String urn) {

    public static final String DEFAULT_NAMESPACE = "codery";

    public static final String DEFAULT_TYPE = "entity";

    private static final String SEGMENT = "[A-Za-z0-9][A-Za-z0-9-]*";

    private static final Pattern URN_PATTERN = Pattern.compile(
            "^urn:(" + SEGMENT + "):(" + SEGMENT + "):"
            + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$"
    );

    public EntityId {
        Objects.requireNonNull(urn, "urn");
        if (!URN_PATTERN.matcher(urn).matches()) {
            throw new IllegalArgumentException("Invalid entity URN: '" + urn + "'");
        }
    }

    /** Builds an entity ID from a namespace, type and UUID. */
    public static EntityId of(String namespace, String type, UUID uuid) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(uuid, "uuid");
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        return new EntityId("urn:" + namespace + ":" + type + ":" + uuid);
    }

    /** Builds an entity ID from a namespace and UUID under the default {@code entity} type. */
    public static EntityId of(String namespace, UUID uuid) {
        return of(namespace, DEFAULT_TYPE, uuid);
    }

    /** Builds an entity ID under the default {@code codery} namespace and {@code entity} type. */
    public static EntityId of(UUID uuid) {
        return of(DEFAULT_NAMESPACE, DEFAULT_TYPE, uuid);
    }

    /** Returns the namespace component. */
    public String namespace() {
        return parsed().group(1);
    }

    /** Returns the type component. */
    public String type() {
        return parsed().group(2);
    }

    /** Returns the UUID component. */
    public UUID uuid() {
        return UUID.fromString(parsed().group(3));
    }

    private MatchResult parsed() {
        return URN_PATTERN.matcher(urn).results()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Validated URN failed re-parse: " + urn));
    }

    @Override
    public String toString() {
        return urn;
    }
}
