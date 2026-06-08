package uk.codery.jclaim.storage.mongo;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.storage.mongo.support.MongoTestSupport;
import uk.codery.jclaim.storage.mongo.support.RequiresDockerCondition;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the adapter's auto-created indexes match what the port contract
 * depends on for atomic alias uniqueness and publicId uniqueness.
 */
@ExtendWith(RequiresDockerCondition.class)
final class MongoIndexesTest {

    @Test
    void createIndexes_landsBothUniqueIndexesOnTheCollection() {
        MongoCollection<Document> collection = MongoTestSupport.newCollection();
        MongoEntityStorage.create(collection); // creates indexes as a side-effect

        List<Document> indexes = new ArrayList<>();
        collection.listIndexes().forEach(indexes::add);

        assertThat(indexes)
                .anyMatch(idx -> MongoEntityStorage.INDEX_PUBLIC_ID.equals(idx.getString("name"))
                        && Boolean.TRUE.equals(idx.getBoolean("unique"))
                        // publicId is opt-in: the unique index is partial so that
                        // documents without the field don't collide on null.
                        && idx.containsKey("partialFilterExpression"))
                .anyMatch(idx -> MongoEntityStorage.INDEX_ALIASES.equals(idx.getString("name"))
                        && Boolean.TRUE.equals(idx.getBoolean("unique"))
                        && idx.get("key", Document.class).containsKey("aliases.source")
                        && idx.get("key", Document.class).containsKey("aliases.sourceId"))
                .anyMatch(idx -> MongoEntityStorage.INDEX_ATTRIBUTES.equals(idx.getString("name"))
                        && idx.get("key", Document.class).containsKey("attributes.name")
                        && idx.get("key", Document.class).containsKey("attributes.value"));
    }
}
