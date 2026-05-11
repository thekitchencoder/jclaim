package uk.codery.jclaim.event;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Emitted when {@code resolveOrMint} matches a stored entity via its
 * {@code (source, sourceId)} alias <em>and</em> the incoming claim's
 * attributes disagree with the stored attributes. The stored entity is
 * <strong>not</strong> mutated — the resolver returns the entity unchanged
 * and surfaces the conflict so a steward can decide what to do.
 *
 * <p>Listeners receive these via the {@link ConflictEventSink} configured on
 * the resolver.
 */
public record EntityAttributesConflicted(
        Entity stored,
        Claim incoming,
        List<AttributeDiff> differences,
        Instant occurredAt
) {

    public EntityAttributesConflicted {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(differences, "differences");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (differences.isEmpty()) {
            throw new IllegalArgumentException("differences must not be empty");
        }
        differences = List.copyOf(differences);
    }
}
