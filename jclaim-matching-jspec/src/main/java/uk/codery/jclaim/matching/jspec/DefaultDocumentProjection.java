package uk.codery.jclaim.matching.jspec;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.MatchingAttribute;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless default {@link DocumentProjection}. Folds each side's
 * {@link MatchingAttribute} list into a flat {@code name -> value} map and
 * nests it under {@code "claim"} / {@code "candidate"}.
 */
final class DefaultDocumentProjection implements DocumentProjection {

    static final DefaultDocumentProjection INSTANCE = new DefaultDocumentProjection();

    private DefaultDocumentProjection() {}

    @Override
    public Projected project(Claim claim, Entity candidate) {
        return new Projected(
                Map.of("claim", attrsToMap(claim.attributes())),
                Map.of("candidate", attrsToMap(candidate.attributes())));
    }

    /**
     * Folds attributes into a {@code name -> value} map. On duplicate names the
     * <em>last</em> attribute in iteration order wins — attributes are an
     * ordered list, so a later assertion is treated as superseding an earlier
     * one for projection purposes. A {@link LinkedHashMap} preserves first-seen
     * key order for deterministic, readable documents.
     */
    private static Map<String, Object> attrsToMap(List<MatchingAttribute> attributes) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (MatchingAttribute attr : attributes) {
            map.put(attr.name(), attr.value());
        }
        return map;
    }
}
