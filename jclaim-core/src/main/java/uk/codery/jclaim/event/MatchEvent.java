package uk.codery.jclaim.event;

/**
 * Sealed surface of stewardship events emitted by the resolver during
 * {@code resolveOrMint}. Each variant captures a situation a steward may want
 * to review — without the resolver ever silently guessing or mutating stored
 * state.
 *
 * <ul>
 *   <li>{@link EntityAttributesConflicted} — a matched entity's stored
 *       attributes disagree with the incoming claim,</li>
 *   <li>{@link MatchUndecided} — no candidate matched conclusively, so a new
 *       entity was minted while at least one candidate remained undetermined,</li>
 *   <li>{@link MatchAmbiguous} — more than one candidate matched; a winner was
 *       chosen deterministically and the alternatives are surfaced.</li>
 *   <li>{@link CandidatePoolTruncated} — the candidate pool hit the cap and was
 *       truncated, so the matching policy may not have seen the true match.</li>
 * </ul>
 *
 * <p>Listeners receive these via the {@link MatchEventSink} configured on the
 * resolver.
 */
public sealed interface MatchEvent
        permits EntityAttributesConflicted, MatchUndecided, MatchAmbiguous, CandidatePoolTruncated {
}
