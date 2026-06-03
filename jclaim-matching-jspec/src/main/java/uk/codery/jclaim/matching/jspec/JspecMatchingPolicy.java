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
import java.util.Objects;

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

    private JspecMatchingPolicy(Specification spec,
                                DocumentProjection projection,
                                OutcomeAggregator aggregator) {
        this.evaluator = new SpecificationEvaluator(Objects.requireNonNull(spec, "spec"));
        this.projection = Objects.requireNonNull(projection, "projection");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    @Override
    public TriState evaluate(Claim claim, Entity candidate) {
        DocumentProjection.Projected p = projection.project(claim, candidate);
        EvaluationOutcome outcome = evaluator.evaluate(p.target(), p.context());
        return aggregator.aggregate(outcome);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Loads a YAML (or JSON) spec from the classpath and builds a policy with
     * the default projection and aggregator.
     *
     * @param path classpath resource path (e.g. {@code "/matching/spec.yaml"})
     * @throws IllegalArgumentException if the resource is not found
     * @throws UncheckedIOException     if the resource cannot be parsed
     */
    public static JspecMatchingPolicy fromResource(String path) {
        Objects.requireNonNull(path, "path");
        try (InputStream in = JspecMatchingPolicy.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Matching spec resource not found on classpath: " + path);
            }
            return builder().spec(YAML.readValue(in, Specification.class)).build();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load matching spec from resource: " + path, e);
        }
    }

    /**
     * Parses a YAML (or JSON) spec from a string and builds a policy with the
     * default projection and aggregator.
     *
     * @throws UncheckedIOException if {@code specText} cannot be parsed
     */
    public static JspecMatchingPolicy fromString(String specText) {
        Objects.requireNonNull(specText, "specText");
        try {
            return builder().spec(YAML.readValue(specText, Specification.class)).build();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse matching spec", e);
        }
    }

    /** Builder for {@link JspecMatchingPolicy}. A {@link #spec(Specification)} is required. */
    public static final class Builder {

        private Specification spec;
        private DocumentProjection projection = DocumentProjection.defaults();
        private OutcomeAggregator aggregator = OutcomeAggregator.conjunctive();

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

        public JspecMatchingPolicy build() {
            return new JspecMatchingPolicy(
                    Objects.requireNonNull(spec, "spec is required"),
                    projection, aggregator);
        }
    }
}
