package uk.codery.jclaim.property;

import uk.codery.jclaim.fixtures.EntityFixtures;
import uk.codery.jclaim.model.Claim;

import java.util.List;
import java.util.Map;

/**
 * Loader for the synthetic UK property dataset under
 * {@code src/test/resources/property-fixtures/}. Thin wrapper over
 * {@link EntityFixtures} that exposes property-shaped accessor names so
 * property-specific tests read naturally.
 *
 * <p>The dataset is a fixed test asset — see
 * {@code property-fixtures/README.md} for the layout and scenario
 * coverage.
 */
public final class PropertyFixtures {

    private static final String DIRECTORY = "property-fixtures";
    private static final String ENTITIES_FILE = "properties";

    private final EntityFixtures delegate;

    private PropertyFixtures(EntityFixtures delegate) {
        this.delegate = delegate;
    }

    /** Loads the baked-in property fixtures from the classpath. */
    public static PropertyFixtures load() {
        return new PropertyFixtures(EntityFixtures.load(DIRECTORY, ENTITIES_FILE));
    }

    /** Ground-truth mapping: synthetic property-id → claims that must reconcile together. */
    public Map<String, List<Claim>> claimsByProperty() {
        return delegate.claimsById();
    }

    /** Flat list of every claim across every source system, suitable for ingestion. */
    public List<Claim> allClaims() {
        return delegate.allClaims();
    }

    /** Claims that re-assert an existing alias with mutated attributes (conflict scenarios). */
    public List<Claim> updateClaims() {
        return delegate.updateClaims();
    }

    /** Number of properties in the ground truth. */
    public int propertyCount() {
        return delegate.entityCount();
    }

    /** Claims belonging to a single property in the ground truth. */
    public List<Claim> claimsFor(String propertyId) {
        return delegate.claimsFor(propertyId);
    }
}
