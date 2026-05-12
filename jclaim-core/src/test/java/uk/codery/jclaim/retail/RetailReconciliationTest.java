package uk.codery.jclaim.retail;

import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

/**
 * In-memory binding of {@link AbstractRetailReconciliationTest}. The Postgres
 * and Mongo adapter modules carry their own concrete subclasses against
 * Testcontainers-managed databases.
 */
final class RetailReconciliationTest extends AbstractRetailReconciliationTest {

    @Override
    protected EntityStorage newStorage() {
        return new InMemoryEntityStorage();
    }
}
