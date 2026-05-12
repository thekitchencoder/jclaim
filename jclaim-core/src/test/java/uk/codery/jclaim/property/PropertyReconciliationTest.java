package uk.codery.jclaim.property;

import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

/** In-memory binding of {@link AbstractPropertyReconciliationTest}. */
final class PropertyReconciliationTest extends AbstractPropertyReconciliationTest {

    @Override
    protected EntityStorage newStorage() {
        return new InMemoryEntityStorage();
    }
}
