package uk.codery.jclaim.spring.health;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.storage.EntityStorage;

import java.util.Objects;

/**
 * Reports {@code UP} when the configured {@link EntityStorage} responds
 * to a cheap probe — a {@link EntityStorage#findByAlias} lookup on a
 * deliberately-impossible alias. Implementations that throw on lookup
 * surface as {@code DOWN}; otherwise the indicator returns {@code UP}
 * with the storage adapter class name as a detail.
 */
public final class JclaimHealthIndicator extends AbstractHealthIndicator {

    static final Alias PROBE = new Alias(
            SourceSystem.of("__jclaim_health_probe__"),
            "__health-check__");

    private final EntityStorage storage;

    public JclaimHealthIndicator(EntityStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        storage.findByAlias(PROBE);
        builder.up().withDetail("storage", storage.getClass().getSimpleName());
    }
}
