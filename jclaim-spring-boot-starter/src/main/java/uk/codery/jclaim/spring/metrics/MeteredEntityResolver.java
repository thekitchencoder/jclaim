package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decorator that records resolver-method counters and timers. Wraps an
 * upstream {@link EntityResolver} and forwards every call; instruments
 * {@code resolveOrMint} with a duration timer and an outcome-tagged
 * counter (matched/minted), and {@code findCandidates} with a
 * standalone counter. All other operations forward unchanged.
 *
 * <p>In multi-type mode every per-type resolver is decorated with its own
 * {@code MeteredEntityResolver} carrying a {@code type=<entity-type>} tag, so
 * the same meter names ({@code jclaim.resolve}, {@code jclaim.resolve.duration},
 * {@code jclaim.findCandidates}) carry per-type series. The single-type path
 * uses the 2-arg constructor and emits untagged meters (no {@code type} tag),
 * preserving the historic single-resolver behaviour.
 */
public final class MeteredEntityResolver implements EntityResolver {

    private final EntityResolver delegate;
    private final MeterRegistry registry;
    private final Timer resolveTimer;
    private final String type;

    public MeteredEntityResolver(EntityResolver delegate, MeterRegistry registry) {
        this(delegate, registry, null);
    }

    /**
     * Type-tagged variant: every meter this decorator registers carries a
     * {@code type=<type>} tag. A {@code null} or blank {@code type} reverts to
     * the untagged single-type behaviour.
     *
     * @param type the entity type this resolver reconciles, used as the
     *             {@code type} meter tag; {@code null}/blank for no tag
     */
    public MeteredEntityResolver(EntityResolver delegate, MeterRegistry registry, String type) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.type = (type == null || type.isBlank()) ? null : type;
        Timer.Builder timerBuilder = Timer.builder("jclaim.resolve.duration");
        if (this.type != null) {
            timerBuilder.tag("type", this.type);
        }
        this.resolveTimer = timerBuilder.register(registry);
    }

    @Override
    public ResolutionResult resolveOrMint(Claim claim) {
        return resolveTimer.record(() -> {
            ResolutionResult result = delegate.resolveOrMint(claim);
            String outcome = switch (result) {
                case ResolutionResult.Matched m -> "matched";
                case ResolutionResult.Minted m -> "minted";
            };
            if (type != null) {
                registry.counter("jclaim.resolve", "type", type, "outcome", outcome).increment();
            } else {
                registry.counter("jclaim.resolve", "outcome", outcome).increment();
            }
            return result;
        });
    }

    @Override
    public Entity getByUrn(EntityId urn) {
        return delegate.getByUrn(urn);
    }

    @Override
    public Optional<Entity> findByHumanId(String humanId) {
        return delegate.findByHumanId(humanId);
    }

    @Override
    public Optional<Entity> findByAlias(SourceSystem source, String sourceId) {
        return delegate.findByAlias(source, sourceId);
    }

    @Override
    public Entity addAlias(EntityId urn, SourceSystem source, String sourceId) {
        return delegate.addAlias(urn, source, sourceId);
    }

    @Override
    public Set<Entity> findCandidates(Claim claim) {
        if (type != null) {
            registry.counter("jclaim.findCandidates", "type", type).increment();
        } else {
            registry.counter("jclaim.findCandidates").increment();
        }
        return delegate.findCandidates(claim);
    }
}
