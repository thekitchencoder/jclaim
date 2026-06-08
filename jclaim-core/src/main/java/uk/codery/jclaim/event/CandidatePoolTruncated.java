package uk.codery.jclaim.event;

import uk.codery.jclaim.model.Claim;

import java.util.Objects;

/**
 * Emitted when {@code resolveOrMint}'s candidate pool hit the resolver's cap and
 * was (assumed) truncated: the matching policy scored only a capped subset, so the
 * true match may have been excluded before scoring — a possible false mint, or a
 * missed / hidden-ambiguous match, worth a steward's review. Fires on
 * <strong>every</strong> truncated {@code resolveOrMint} regardless of outcome
 * (mint, single match, ambiguous, or undecided), independently of the decision
 * events; a steward correlates them by the shared {@link #claim()}.
 *
 * <p>The payload is deliberately lean. Under truncation the returned pool is an
 * arbitrary capped subset (the real match may not even be in it), so it is not
 * carried here. {@code claim} identifies the input; {@code cap} is the actionable
 * number — the {@code maxCandidates} limit that was hit.
 *
 * @param claim the inbound claim whose candidate pool was truncated
 * @param cap   the {@code maxCandidates} limit that was hit
 */
public record CandidatePoolTruncated(Claim claim, int cap) implements MatchEvent {

    public CandidatePoolTruncated {
        Objects.requireNonNull(claim, "claim");
    }
}
