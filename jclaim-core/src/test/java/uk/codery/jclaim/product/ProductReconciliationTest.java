package uk.codery.jclaim.product;

import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

/** In-memory binding of {@link AbstractProductReconciliationTest}. */
final class ProductReconciliationTest extends AbstractProductReconciliationTest {

    @Override
    protected EntityStorage newStorage() {
        return new InMemoryEntityStorage();
    }
}
