package uk.codery.jclaim.event;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;

import java.util.List;
import java.util.Objects;

/**
 * Emitted when {@code resolveOrMint}'s matching policy returned more than one
 * {@code MATCHED} candidate for a claim. The resolver links the claim to a
 * deterministically chosen {@code winner} (oldest by creation time, then by
 * URN) and surfaces the other matched entities so a steward can decide whether
 * the candidates should be merged.
 *
 * @param claim                  the inbound claim being resolved
 * @param winner                 the entity the resolver linked the claim to
 * @param otherMatched           the other entities the policy also matched
 * @param candidates             every candidate considered, with its policy verdict
 * @param candidatesConsidered   how many candidates the policy actually evaluated
 * @param candidatesFound        how many candidates the storage adapter returned
 */
public record MatchAmbiguous(
        Claim claim,
        Entity winner,
        List<Entity> otherMatched,
        List<CandidateOutcome> candidates,
        int candidatesConsidered,
        int candidatesFound
) implements MatchEvent {

    public MatchAmbiguous {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(winner, "winner");
        Objects.requireNonNull(otherMatched, "otherMatched");
        Objects.requireNonNull(candidates, "candidates");
        otherMatched = List.copyOf(otherMatched);
        candidates = List.copyOf(candidates);
    }
}
