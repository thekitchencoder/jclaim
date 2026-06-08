package uk.codery.jclaim.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical reconciled entity. Holds the URN, a nullable public lookup
 * ID, the alias graph of contributing source-system claims, the attributes
 * recorded on the entity, an optional {@code supersededBy} pointer for future
 * merge operations, and creation / update timestamps.
 *
 * <p>The {@code publicId} is <strong>nullable</strong>: it is an independently
 * minted lookup attribute, not part of the entity's core identity. An entity
 * type that mints no public ID carries {@code publicId == null}. When
 * present it must be non-blank.
 *
 * <p>Records are immutable; mutating operations on the resolver return new
 * instances. Defensive copies are taken of the alias and attribute lists.
 */
public record Entity(
        EntityId id,
        String publicId,
        List<Alias> aliases,
        List<MatchingAttribute> attributes,
        EntityId supersededBy,
        Instant createdAt,
        Instant updatedAt
) {

    public Entity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(aliases, "aliases");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        // publicId is nullable: absent means this entity type mints no publicId.
        // When present it must be non-blank.
        if (publicId != null && publicId.isBlank()) {
            throw new IllegalArgumentException("publicId must not be blank when present");
        }
        aliases = List.copyOf(aliases);
        attributes = List.copyOf(attributes);
        // supersededBy may legitimately be null
    }

    /** Convenience accessor for the (nullable) supersedence pointer. */
    public Optional<EntityId> supersededByOpt() {
        return Optional.ofNullable(supersededBy);
    }

    /** Returns a copy with {@code alias} appended (no-op if already present). */
    public Entity withAlias(Alias alias, Instant updatedAt) {
        Objects.requireNonNull(alias, "alias");
        if (aliases.contains(alias)) {
            return this;
        }
        java.util.ArrayList<Alias> next = new java.util.ArrayList<>(aliases.size() + 1);
        next.addAll(aliases);
        next.add(alias);
        return new Entity(id, publicId, next, attributes, supersededBy, createdAt, updatedAt);
    }
}
