package uk.codery.jclaim.fixtures;

import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.resolver.EntityResolver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays the role of an external matching policy for the corpus integration
 * tests. For each claim, looks up which ground-truth entity it belongs to,
 * mints a new entity the first time that ground-truth id is seen, and
 * attaches subsequent claims as aliases on the already-minted entity.
 *
 * <p>This is the path the future JSpec-driven matching policy will exercise
 * once it lands: today the alias attachment is decided by the test
 * ground-truth, tomorrow it will be decided by attribute predicates.
 */
public final class GroundTruthIngester {

    private GroundTruthIngester() {
    }

    /**
     * Ingests {@code orderedClaims} into {@code resolver}, using
     * {@code groundTruth} to decide whether each claim mints a fresh
     * entity or attaches as an alias on an existing one.
     */
    public static void ingest(
            EntityResolver resolver,
            List<Claim> orderedClaims,
            Map<String, List<Claim>> groundTruth) {
        Map<Alias, String> aliasToGroundTruth = new HashMap<>();
        for (Map.Entry<String, List<Claim>> e : groundTruth.entrySet()) {
            for (Claim c : e.getValue()) {
                aliasToGroundTruth.put(c.asAlias(), e.getKey());
            }
        }

        Map<String, EntityId> idToEntity = new HashMap<>();
        for (Claim claim : orderedClaims) {
            String groundTruthId = aliasToGroundTruth.get(claim.asAlias());
            EntityId existing = idToEntity.get(groundTruthId);
            if (existing != null) {
                resolver.addAlias(existing, claim.source(), claim.sourceId());
            } else {
                Entity minted = resolver.resolveOrMint(claim).entity();
                idToEntity.put(groundTruthId, minted.id());
            }
        }
    }
}
