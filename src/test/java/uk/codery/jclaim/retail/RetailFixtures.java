package uk.codery.jclaim.retail;

import uk.codery.jclaim.fixtures.EntityFixtures;
import uk.codery.jclaim.model.Claim;

import java.util.List;
import java.util.Map;

/**
 * Loader for the retail synthetic customer dataset under
 * {@code src/test/resources/retail-fixtures/}. Thin wrapper over
 * {@link EntityFixtures} that exposes customer-shaped accessor names so
 * retail-specific tests read naturally.
 *
 * <p>The dataset is a fixed test asset — see
 * {@code retail-fixtures/README.md} for the layout and scenario coverage.
 */
public final class RetailFixtures {

    private static final String DIRECTORY = "retail-fixtures";
    private static final String ENTITIES_FILE = "customers";

    private final EntityFixtures delegate;

    private RetailFixtures(EntityFixtures delegate) {
        this.delegate = delegate;
    }

    /** Loads the baked-in retail fixtures from the classpath. */
    public static RetailFixtures load() {
        return new RetailFixtures(EntityFixtures.load(DIRECTORY, ENTITIES_FILE));
    }

    /** Ground-truth mapping: synthetic customer-id → claims that must reconcile together. */
    public Map<String, List<Claim>> claimsByCustomer() {
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

    /** Number of customers in the ground truth. */
    public int customerCount() {
        return delegate.entityCount();
    }

    /** Claims belonging to a single customer in the ground truth. */
    public List<Claim> claimsFor(String customerId) {
        return delegate.claimsFor(customerId);
    }
}
