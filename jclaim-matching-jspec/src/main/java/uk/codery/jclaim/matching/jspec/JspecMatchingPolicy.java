package uk.codery.jclaim.matching.jspec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import uk.codery.jclaim.matching.MatchingPolicy;
import uk.codery.jclaim.matching.TriState;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jspec.evaluator.SpecificationEvaluator;
import uk.codery.jspec.model.Specification;
import uk.codery.jspec.result.EvaluationOutcome;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link MatchingPolicy} backed by a jspec {@link Specification}. Each
 * {@code (Claim, candidate)} pair is projected into a target/context document
 * pair by a {@link DocumentProjection}; the spec is evaluated against them via
 * jspec's two-arg {@link SpecificationEvaluator#evaluate(Object, Object)} (so
 * {@code $contextPath} operands late-bind against the candidate context); and
 * the resulting {@link EvaluationOutcome} is collapsed to a {@link TriState} by
 * an {@link OutcomeAggregator}.
 *
 * <p>Instances are immutable and thread-safe: a single {@link
 * SpecificationEvaluator} (thread-safe per jspec) is built from the spec once
 * and reused across all evaluations.
 */
public final class JspecMatchingPolicy implements MatchingPolicy {

    /** Reused for both YAML and JSON spec text — YAML is a JSON superset. */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final SpecificationEvaluator evaluator;
    private final DocumentProjection projection;
    private final OutcomeAggregator aggregator;
    private final Set<String> blockingKeys;

    private JspecMatchingPolicy(Specification spec,
                                DocumentProjection projection,
                                OutcomeAggregator aggregator,
                                Set<String> blockingKeys) {
        this.evaluator = new SpecificationEvaluator(Objects.requireNonNull(spec, "spec"));
        this.projection = Objects.requireNonNull(projection, "projection");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.blockingKeys = Set.copyOf(Objects.requireNonNull(blockingKeys, "blockingKeys"));
    }

    @Override
    public TriState evaluate(Claim claim, Entity candidate) {
        DocumentProjection.Projected p = projection.project(claim, candidate);
        EvaluationOutcome outcome = evaluator.evaluate(p.target(), p.context());
        return aggregator.aggregate(outcome);
    }

    /**
     * The blocking keys this policy declares. Empty (the default) means the
     * resolver blocks on every attribute; a non-empty set narrows the candidate
     * pool to a projection of the claim onto these names. Configured via the
     * builder or the keyed {@link #fromResource(String, Collection)} /
     * {@link #fromString(String, Collection)} factories.
     */
    @Override
    public Set<String> blockingKeys() {
        return blockingKeys;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Loads a YAML (or JSON) spec from the classpath and builds a policy with
     * the default projection and aggregator and <em>no</em> blocking keys
     * (blocks on every attribute).
     *
     * @param path classpath resource path (e.g. {@code "/matching/spec.yaml"})
     * @throws IllegalArgumentException if the resource is not found
     * @throws UncheckedIOException     if the resource cannot be parsed
     */
    public static JspecMatchingPolicy fromResource(String path) {
        return fromResource(path, Set.of());
    }

    /**
     * Loads a YAML (or JSON) spec from the classpath and builds a policy with
     * the default projection and aggregator and the given blocking keys.
     *
     * @param path classpath resource path (e.g. {@code "/matching/spec.yaml"})
     * @param keys blocking-key names (see {@link #blockingKeys()}); may be empty
     * @throws IllegalArgumentException if the resource is not found, or a key is blank
     * @throws UncheckedIOException     if the resource cannot be parsed
     */
    public static JspecMatchingPolicy fromResource(String path, Collection<String> keys) {
        Objects.requireNonNull(path, "path");
        try (InputStream in = JspecMatchingPolicy.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Matching spec resource not found on classpath: " + path);
            }
            return builder().spec(YAML.readValue(in, Specification.class)).blockingKeys(keys).build();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load matching spec from resource: " + path, e);
        }
    }

    /**
     * Parses a YAML (or JSON) spec from a string and builds a policy with the
     * default projection and aggregator and <em>no</em> blocking keys.
     *
     * @throws UncheckedIOException if {@code specText} cannot be parsed
     */
    public static JspecMatchingPolicy fromString(String specText) {
        return fromString(specText, Set.of());
    }

    /**
     * Parses a YAML (or JSON) spec from a string and builds a policy with the
     * default projection and aggregator and the given blocking keys.
     *
     * @throws UncheckedIOException      if {@code specText} cannot be parsed
     * @throws IllegalArgumentException  if a key is blank
     */
    public static JspecMatchingPolicy fromString(String specText, Collection<String> keys) {
        Objects.requireNonNull(specText, "specText");
        try {
            return builder().spec(YAML.readValue(specText, Specification.class)).blockingKeys(keys).build();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse matching spec", e);
        }
    }

    /** Builder for {@link JspecMatchingPolicy}. A {@link #spec(Specification)} is required. */
    public static final class Builder {

        private Specification spec;
        private DocumentProjection projection = DocumentProjection.defaults();
        private OutcomeAggregator aggregator = OutcomeAggregator.conjunctive();
        private Set<String> blockingKeys = Set.of();

        private Builder() {}

        /** The matching specification. Required. */
        public Builder spec(Specification spec) {
            this.spec = spec;
            return this;
        }

        /** Document projection. Defaults to {@link DocumentProjection#defaults()}. */
        public Builder projection(DocumentProjection projection) {
            this.projection = Objects.requireNonNull(projection, "projection");
            return this;
        }

        /** Outcome aggregator. Defaults to {@link OutcomeAggregator#conjunctive()}. */
        public Builder aggregate(OutcomeAggregator aggregator) {
            this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
            return this;
        }

        /**
         * Blocking-key names (see {@link JspecMatchingPolicy#blockingKeys()}).
         * Defaults to none (block on every attribute). Each name must be
         * non-null and non-blank — a blank name matches no attribute and is
         * always a misconfiguration.
         */
        public Builder blockingKeys(Collection<String> keys) {
            Objects.requireNonNull(keys, "keys");
            Set<String> copy = new LinkedHashSet<>();
            for (String k : keys) {
                if (k == null || k.isBlank()) {
                    throw new IllegalArgumentException(
                            "blocking key names must be non-null and non-blank; got: " + keys);
                }
                copy.add(k);
            }
            this.blockingKeys = Set.copyOf(copy);
            return this;
        }

        public JspecMatchingPolicy build() {
            return new JspecMatchingPolicy(
                    Objects.requireNonNull(spec, "spec is required"),
                    projection, aggregator, blockingKeys);
        }
    }
}
