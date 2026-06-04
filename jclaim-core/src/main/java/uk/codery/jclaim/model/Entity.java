package uk.codery.jclaim.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical reconciled entity. Holds the URN, a nullable human-friendly lookup
 * ID, the alias graph of contributing source-system claims, the attributes
 * recorded on the entity, an optional {@code supersededBy} pointer for future
 * merge operations, and creation / update timestamps.
 *
 * <p>The {@code humanId} is <strong>nullable</strong>: it is an independently
 * minted lookup attribute, not part of the entity's core identity. An entity
 * type that mints no human-friendly ID carries {@code humanId == null}. When
 * present it must be non-blank.
 *
 * <p>Records are immutable; mutating operations on the resolver return new
 * instances. Defensive copies are taken of the alias and attribute lists.
 */
public record Entity(
        EntityId id,
        String humanId,
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
        // humanId is nullable: absent means this entity type mints no humanId.
        // When present it must be non-blank.
        if (humanId != null && humanId.isBlank()) {
            throw new IllegalArgumentException("humanId must not be blank when present");
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
        return new Entity(id, humanId, next, attributes, supersededBy, createdAt, updatedAt);
    }
}
