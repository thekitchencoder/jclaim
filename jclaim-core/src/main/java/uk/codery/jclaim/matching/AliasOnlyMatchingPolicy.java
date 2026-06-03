package uk.codery.jclaim.matching;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;

/**
 * Alias-only {@link MatchingPolicy}: a candidate is {@link TriState#MATCHED}
 * iff its alias graph already contains the claim's {@code (source, sourceId)}
 * alias, otherwise {@link TriState#NOT_MATCHED}. Never returns
 * {@link TriState#UNDETERMINED}.
 *
 * <p>Stateless shared singleton — see {@link MatchingPolicy#aliasOnly()}.
 */
enum AliasOnlyMatchingPolicy implements MatchingPolicy {

    INSTANCE;

    @Override
    public TriState evaluate(Claim claim, Entity candidate) {
        return candidate.aliases().contains(claim.asAlias())
            ? TriState.MATCHED
            : TriState.NOT_MATCHED;
    }
}
