package uk.codery.jclaim.storage.mongo;

import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.EntityStorageContract;
import uk.codery.jclaim.storage.mongo.support.MongoTestSupport;
import uk.codery.jclaim.storage.mongo.support.RequiresDockerCondition;

/**
 * Pins {@link MongoEntityStorage} to the storage port behavioural contract
 * against a real MongoDB instance. Each test gets a fresh collection so the
 * contract sees an empty namespace.
 */
@ExtendWith(RequiresDockerCondition.class)
final class MongoEntityStorageContractTest extends EntityStorageContract {

    @Override
    protected EntityStorage newStorage() {
        return MongoTestSupport.freshStorage();
    }
}
