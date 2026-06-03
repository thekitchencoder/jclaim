/**
 * JSpec-backed implementation of the {@link uk.codery.jclaim.matching.MatchingPolicy}
 * port. Every type that references a {@code uk.codery.jspec} type lives in this
 * package, keeping the jspec dependency off {@code jclaim-core}'s classpath.
 *
 * <p>The provider projects each {@code (Claim, candidate)} pair into a
 * {@code (target, context)} document pair and evaluates a JSpec
 * {@link uk.codery.jspec.model.Specification} whose {@code $contextPath} operands
 * late-bind against the candidate context.
 */
package uk.codery.jclaim.matching.jspec;
