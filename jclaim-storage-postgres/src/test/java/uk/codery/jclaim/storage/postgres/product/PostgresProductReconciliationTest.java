package uk.codery.jclaim.storage.postgres.product;

import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.product.AbstractProductReconciliationTest;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.postgres.support.PostgresTestSupport;
import uk.codery.jclaim.storage.postgres.support.RequiresDockerCondition;

@ExtendWith(RequiresDockerCondition.class)
final class PostgresProductReconciliationTest extends AbstractProductReconciliationTest {

    @Override
    protected EntityStorage newStorage() {
        return PostgresTestSupport.freshStorage();
    }
}
