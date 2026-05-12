package uk.codery.jclaim.storage.mongo.property;

import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.property.AbstractPropertyReconciliationTest;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.mongo.support.MongoTestSupport;
import uk.codery.jclaim.storage.mongo.support.RequiresDockerCondition;

@ExtendWith(RequiresDockerCondition.class)
final class MongoPropertyReconciliationTest extends AbstractPropertyReconciliationTest {

    @Override
    protected EntityStorage newStorage() {
        return MongoTestSupport.freshStorage();
    }
}
