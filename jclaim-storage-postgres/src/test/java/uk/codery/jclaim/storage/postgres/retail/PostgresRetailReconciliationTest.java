package uk.codery.jclaim.storage.postgres.retail;

import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.retail.AbstractRetailReconciliationTest;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.postgres.support.PostgresTestSupport;
import uk.codery.jclaim.storage.postgres.support.RequiresDockerCondition;

@ExtendWith(RequiresDockerCondition.class)
final class PostgresRetailReconciliationTest extends AbstractRetailReconciliationTest {

    @Override
    protected EntityStorage newStorage() {
        return PostgresTestSupport.freshStorage();
    }
}
