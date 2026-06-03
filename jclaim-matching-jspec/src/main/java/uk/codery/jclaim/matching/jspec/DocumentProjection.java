package uk.codery.jclaim.matching.jspec;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;

import java.util.Map;

/**
 * Projects a {@code (Claim, candidate)} pair into the two documents a jspec
 * {@link uk.codery.jspec.evaluator.SpecificationEvaluator} consumes: a
 * <em>target</em> document (evaluated against the spec's queries) and a
 * <em>context</em> document (against which {@code $contextPath} operands
 * late-bind).
 *
 * <p>The shape of those documents is the contract between a matching spec and
 * the claims it scores — a spec author writes paths like {@code claim.email}
 * and {@code candidate.email} expecting this projection to populate them.
 *
 * <p>Implementations must be stateless and thread-safe.
 */
public interface DocumentProjection {

    /** The pair of documents handed to the evaluator. */
    record Projected(Map<String, Object> target, Map<String, Object> context) {}

    /** Projects {@code claim} and {@code candidate} into evaluator documents. */
    Projected project(Claim claim, Entity candidate);

    /**
     * The default projection: the claim's attributes are folded into a flat
     * {@code name -> value} map and nested under the {@code "claim"} key of the
     * target document; the candidate's attributes likewise under
     * {@code "candidate"} in the context document. A spec therefore references
     * {@code claim.<attr>} in its queries and
     * {@code Map.of("$contextPath", "candidate.<attr>")} in its operands.
     *
     * <p>The returned instance is a stateless shared singleton.
     */
    static DocumentProjection defaults() {
        return DefaultDocumentProjection.INSTANCE;
    }
}
