package uk.codery.jclaim.event;

/**
 * One axis of divergence between a stored entity and an incoming claim's
 * attributes. A diff is only produced for attribute names present on
 * <em>both</em> sides with differing values:
 *
 * <ul>
 *   <li>{@code stored} — what the entity records for this attribute,</li>
 *   <li>{@code incoming} — what the claim asserts for this attribute.</li>
 * </ul>
 *
 * <p>Stewardship logic typically iterates over a list of these when handling
 * an {@link EntityAttributesConflicted} event.
 */
public record AttributeDiff(String name, Object stored, Object incoming) {

    public AttributeDiff {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
    }
}
