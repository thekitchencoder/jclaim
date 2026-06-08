package uk.codery.jclaim.event;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;

import java.util.List;
import java.util.Objects;

/**
 * Emitted when {@code resolveOrMint} found candidates for a claim but the
 * matching policy returned no conclusive {@code MATCHED} verdict — at least one
 * candidate was {@code UNDETERMINED}. Rather than silently guessing, the
 * resolver mints a fresh entity and surfaces the undecided candidates for
 * stewardship review.
 *
 * @param claim                  the inbound claim being resolved
 * @param minted                 the new entity the resolver minted
 * @param candidates             every candidate considered, with its policy verdict
 * @param candidatesConsidered   how many candidates the policy actually evaluated
 * @param candidatesFound        how many candidates the storage adapter returned
 */
public record MatchUndecided(
        Claim claim,
        Entity minted,
        List<CandidateOutcome> candidates,
        int candidatesConsidered,
        int candidatesFound
) implements MatchEvent {

    public MatchUndecided {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(minted, "minted");
        Objects.requireNonNull(candidates, "candidates");
        candidates = List.copyOf(candidates);
    }
}
