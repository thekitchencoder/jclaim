package uk.codery.jclaim.event;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;

import java.util.List;
import java.util.Objects;

/**
 * Emitted when {@code resolveOrMint} matches a stored entity <em>and</em> the
 * incoming claim's attributes disagree with the stored attributes. The stored
 * entity is <strong>not</strong> mutated — the resolver returns the entity
 * unchanged and surfaces the conflict so a steward can decide what to do.
 *
 * <p>Only attributes whose names appear on <em>both</em> the stored entity and
 * the claim, with differing values, are reported. Attributes the claim
 * introduces that the entity has never carried are additive, not conflicts, and
 * do not appear in {@code differingValues}.
 *
 * <p>Listeners receive these via the {@link MatchEventSink} configured on the
 * resolver.
 */
public record EntityAttributesConflicted(
        Entity stored,
        Claim claim,
        List<AttributeDiff> differingValues
) implements MatchEvent {

    public EntityAttributesConflicted {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(differingValues, "differingValues");
        if (differingValues.isEmpty()) {
            throw new IllegalArgumentException("differingValues must not be empty");
        }
        differingValues = List.copyOf(differingValues);
    }
}
