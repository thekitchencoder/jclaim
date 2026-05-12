package uk.codery.jclaim.storage.mongo.product;

import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.product.AbstractProductReconciliationTest;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.mongo.support.MongoTestSupport;
import uk.codery.jclaim.storage.mongo.support.RequiresDockerCondition;

@ExtendWith(RequiresDockerCondition.class)
final class MongoProductReconciliationTest extends AbstractProductReconciliationTest {

    @Override
    protected EntityStorage newStorage() {
        return MongoTestSupport.freshStorage();
    }
}
