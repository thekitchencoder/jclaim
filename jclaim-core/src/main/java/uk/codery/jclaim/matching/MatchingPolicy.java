package uk.codery.jclaim.matching;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;

/**
 * Port that scores a single {@code (Claim, candidate)} pair, deciding whether
 * the candidate is the same entity the claim describes.
 *
 * <p>This mirrors the {@code EntityStorage} port/adapter split: the port and
 * the alias-only default ({@link #aliasOnly()}) live in {@code jclaim-core} and
 * carry no jspec dependency, while richer, jspec-expressed policies ship as a
 * swappable provider module ({@code jclaim-matching-jspec}). The resolver
 * depends only on this interface and the alias-only default, so an alternative
 * implementation (hand-coded, Drools, etc.) can replace the matching engine
 * without touching core.
 *
 * <p>Implementations must be thread-safe — the resolver may invoke a single
 * shared instance concurrently across requests.
 */
public interface MatchingPolicy {

    /**
     * Decides whether {@code candidate} is the same entity {@code claim}
     * describes. Returns {@link TriState#MATCHED} when the candidate is the
     * same entity, {@link TriState#NOT_MATCHED} when it is definitively a
     * different entity, and {@link TriState#UNDETERMINED} when there is
     * insufficient evidence to decide.
     */
    TriState evaluate(Claim claim, Entity candidate);

    /**
     * The default, dependency-free policy: a candidate {@code MATCHED} iff its
     * alias graph already contains the claim's {@code (source, sourceId)}
     * alias, else {@code NOT_MATCHED}. Never {@code UNDETERMINED}. This
     * reproduces jclaim's historic alias-only matching behaviour exactly.
     *
     * <p>The returned instance is a stateless shared singleton.
     */
    static MatchingPolicy aliasOnly() {
        return AliasOnlyMatchingPolicy.INSTANCE;
    }
}
