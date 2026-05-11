package uk.codery.jclaim.product;

import uk.codery.jclaim.fixtures.EntityFixtures;
import uk.codery.jclaim.model.Claim;

import java.util.List;
import java.util.Map;

/**
 * Loader for the synthetic product SKU dataset under
 * {@code src/test/resources/product-fixtures/}. Thin wrapper over
 * {@link EntityFixtures} that exposes product-shaped accessor names so
 * product-specific tests read naturally.
 *
 * <p>The dataset is a fixed test asset — see
 * {@code product-fixtures/README.md} for the layout and scenario
 * coverage.
 */
public final class ProductFixtures {

    private static final String DIRECTORY = "product-fixtures";
    private static final String ENTITIES_FILE = "products";

    private final EntityFixtures delegate;

    private ProductFixtures(EntityFixtures delegate) {
        this.delegate = delegate;
    }

    /** Loads the baked-in product fixtures from the classpath. */
    public static ProductFixtures load() {
        return new ProductFixtures(EntityFixtures.load(DIRECTORY, ENTITIES_FILE));
    }

    /** Ground-truth mapping: synthetic product-id → claims that must reconcile together. */
    public Map<String, List<Claim>> claimsByProduct() {
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

    /** Number of products in the ground truth. */
    public int productCount() {
        return delegate.entityCount();
    }

    /** Claims belonging to a single product in the ground truth. */
    public List<Claim> claimsFor(String productId) {
        return delegate.claimsFor(productId);
    }
}
