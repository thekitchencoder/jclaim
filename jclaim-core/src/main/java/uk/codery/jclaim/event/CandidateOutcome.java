package uk.codery.jclaim.event;

import uk.codery.jclaim.matching.TriState;
import uk.codery.jclaim.model.Entity;

import java.util.Objects;

/**
 * The matching-policy result for a single candidate entity considered during
 * {@code resolveOrMint}. Carried inside {@link MatchUndecided} and
 * {@link MatchAmbiguous} so stewards can see how each candidate scored.
 *
 * @param candidate    the entity the policy was evaluated against
 * @param policyResult the policy's {@link TriState} verdict for this candidate
 */
public record CandidateOutcome(Entity candidate, TriState policyResult) {

    public CandidateOutcome {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(policyResult, "policyResult");
    }
}
