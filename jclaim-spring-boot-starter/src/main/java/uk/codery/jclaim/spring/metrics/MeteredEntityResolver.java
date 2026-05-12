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
 */
public final class MeteredEntityResolver implements EntityResolver {

    private final EntityResolver delegate;
    private final MeterRegistry registry;
    private final Timer resolveTimer;

    public MeteredEntityResolver(EntityResolver delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resolveTimer = Timer.builder("jclaim.resolve.duration").register(registry);
    }

    @Override
    public ResolutionResult resolveOrMint(Claim claim) {
        return resolveTimer.record(() -> {
            ResolutionResult result = delegate.resolveOrMint(claim);
            String outcome = switch (result) {
                case ResolutionResult.Matched m -> "matched";
                case ResolutionResult.Minted m -> "minted";
            };
            registry.counter("jclaim.resolve", "outcome", outcome).increment();
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
        registry.counter("jclaim.findCandidates").increment();
        return delegate.findCandidates(claim);
    }
}
