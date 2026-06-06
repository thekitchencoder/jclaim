package uk.codery.jclaim.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Inbound identity assertion from a source system. A claim carries:
 *
 * <ul>
 *   <li>a {@link SourceSystem} naming the originating system,</li>
 *   <li>a {@code sourceId} identifying the record within that system,</li>
 *   <li>a list of {@link MatchingAttribute matching attributes} the source
 *       knows about this entity.</li>
 * </ul>
 *
 * <p>The {@code (source, sourceId)} pair is the alias key used by
 * {@code resolveOrMint} to look up an existing canonical entity. Attributes
 * are recorded on the minted entity, and used for conflict detection when a
 * subsequent claim matches an entity whose stored attributes diverge.
 */
public record Claim(SourceSystem source, String sourceId, List<MatchingAttribute> attributes) {

    public Claim {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(attributes, "attributes");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        attributes = List.copyOf(attributes);
    }

    /** Returns the alias formed by {@code (source, sourceId)}. */
    public Alias asAlias() {
        return new Alias(source, sourceId);
    }

    /**
     * Returns a copy of this claim whose attributes are restricted to those
     * whose name is in {@code names}, preserving {@code source} and
     * {@code sourceId}. The resolver uses this to fetch the candidate pool on a
     * {@code MatchingPolicy}'s declared blocking keys without narrowing the
     * attributes the policy later scores against: blocking sees the projection,
     * scoring sees the full claim. Projecting to an empty set yields a claim
     * with no attributes (which blocks on the alias alone).
     */
    public Claim projectedTo(Set<String> names) {
        Objects.requireNonNull(names, "names");
        List<MatchingAttribute> kept = attributes.stream()
                .filter(a -> names.contains(a.name()))
                .toList();
        return kept.size() == attributes.size()
                ? this
                : new Claim(source, sourceId, kept);
    }
}
