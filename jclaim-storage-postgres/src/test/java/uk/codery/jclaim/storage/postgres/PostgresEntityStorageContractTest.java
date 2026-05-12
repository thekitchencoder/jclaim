package uk.codery.jclaim.storage.postgres;

import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.EntityStorageContract;
import uk.codery.jclaim.storage.postgres.support.PostgresTestSupport;
import uk.codery.jclaim.storage.postgres.support.RequiresDockerCondition;

/**
 * Pins {@link PostgresEntityStorage} to the storage port behavioural contract
 * against a real Postgres instance running in a Testcontainers container.
 * Each test gets a fresh schema so the contract sees an empty database.
 */
@ExtendWith(RequiresDockerCondition.class)
final class PostgresEntityStorageContractTest extends EntityStorageContract {

    @Override
    protected EntityStorage newStorage() {
        return PostgresTestSupport.freshStorage();
    }
}
