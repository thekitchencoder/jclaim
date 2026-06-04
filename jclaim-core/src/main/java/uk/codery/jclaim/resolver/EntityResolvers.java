package uk.codery.jclaim.resolver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, Spring-free registry of {@link EntityResolver}s keyed by entity
 * type. Serves as the selection facade when an application reconciles more
 * than one entity type: callers look a resolver up by its type segment.
 *
 * <p>The backing map is defensively copied at construction, so later mutation
 * of the source map does not affect a registry already created.
 */
public final class EntityResolvers {

    private final Map<String, EntityResolver> byType;

    private EntityResolvers(Map<String, EntityResolver> byType) {
        this.byType = byType;
    }

    /**
     * Builds a registry from {@code byType}. Keys must be non-null and
     * non-blank; values must be non-null. The map is copied, so the caller may
     * continue to mutate the argument without affecting the registry.
     *
     * @throws IllegalArgumentException if any key is blank
     * @throws NullPointerException     if any key or resolver is null
     */
    public static EntityResolvers of(Map<String, EntityResolver> byType) {
        Objects.requireNonNull(byType, "byType");
        Map<String, EntityResolver> copy = new LinkedHashMap<>();
        for (Map.Entry<String, EntityResolver> entry : byType.entrySet()) {
            String type = Objects.requireNonNull(entry.getKey(), "type");
            if (type.isBlank()) {
                throw new IllegalArgumentException("entity type key must not be blank");
            }
            copy.put(type, Objects.requireNonNull(entry.getValue(), "resolver for type " + type));
        }
        return new EntityResolvers(Map.copyOf(copy));
    }

    /** Returns the resolver registered for {@code type}, if any. */
    public Optional<EntityResolver> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    /**
     * Returns the resolver registered for {@code type}, or throws
     * {@link IllegalArgumentException} listing the known types if none is
     * registered.
     */
    public EntityResolver forType(String type) {
        EntityResolver resolver = byType.get(type);
        if (resolver == null) {
            throw new IllegalArgumentException(
                    "No resolver for entity type '" + type + "'. Known types: " + byType.keySet());
        }
        return resolver;
    }

    /** Returns the set of registered entity types. */
    public Set<String> types() {
        return byType.keySet();
    }

    @Override
    public String toString() {
        return "EntityResolvers" + byType.keySet();
    }
}
