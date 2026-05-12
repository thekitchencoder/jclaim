package uk.codery.jclaim.storage.memory;

import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.EntityStorageContract;

/**
 * Pins the in-memory adapter to the storage port behavioural contract. Every
 * assertion lives in {@link EntityStorageContract}; this class only supplies
 * a fresh adapter per test.
 */
final class InMemoryEntityStorageTest extends EntityStorageContract {

    @Override
    protected EntityStorage newStorage() {
        return new InMemoryEntityStorage();
    }
}
