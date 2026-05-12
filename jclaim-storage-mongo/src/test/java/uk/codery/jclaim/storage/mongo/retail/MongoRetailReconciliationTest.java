package uk.codery.jclaim.storage.mongo.retail;

import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.retail.AbstractRetailReconciliationTest;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.mongo.support.MongoTestSupport;
import uk.codery.jclaim.storage.mongo.support.RequiresDockerCondition;

@ExtendWith(RequiresDockerCondition.class)
final class MongoRetailReconciliationTest extends AbstractRetailReconciliationTest {

    @Override
    protected EntityStorage newStorage() {
        return MongoTestSupport.freshStorage();
    }
}
