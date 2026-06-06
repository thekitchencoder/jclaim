package uk.codery.jclaim.matching;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.MatchingAttribute;

import java.util.Set;

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
     * The names of the attributes this policy uses to fetch the candidate pool
     * — its <em>blocking keys</em>. Before scoring, the resolver projects each
     * inbound claim to these names (via {@link Claim#projectedTo(Set)}) and
     * fetches candidates on that projection, so an attribute outside this set is
     * still scored by {@link #evaluate} but never widens the pool.
     *
     * <p>The default returns an empty set, meaning <em>no projection</em>: the
     * resolver blocks on every attribute on the claim, reproducing jclaim's
     * historic behaviour where each {@code (name, value)} pair is a blocking
     * key. A policy that scores on weak signals (name, address, date of birth)
     * but blocks on a smaller, higher-cardinality or derived key set should
     * override this to return just those key names — otherwise a low-cardinality
     * attribute can flood the capped pool and truncate the real match out of it.
     *
     * <p>Returned names must match the {@link MatchingAttribute#name()} values
     * carried on claims and entities exactly (including any normalisation), or
     * the projection will block on nothing.
     *
     * <p>Implementations must <strong>never</strong> return {@code null} —
     * return an empty set to block on every attribute. The resolver enforces
     * this and fails fast with a {@link NullPointerException} on a {@code null}
     * return rather than letting it surface obscurely deeper in resolution.
     */
    default Set<String> blockingKeys() {
        return Set.of();
    }

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
