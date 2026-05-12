package uk.codery.jclaim.storage.postgres.property;

import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.property.AbstractPropertyReconciliationTest;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.postgres.support.PostgresTestSupport;
import uk.codery.jclaim.storage.postgres.support.RequiresDockerCondition;

@ExtendWith(RequiresDockerCondition.class)
final class PostgresPropertyReconciliationTest extends AbstractPropertyReconciliationTest {

    @Override
    protected EntityStorage newStorage() {
        return PostgresTestSupport.freshStorage();
    }
}
