package uk.codery.jclaim.storage.mongo.support;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.testcontainers.containers.MongoDBContainer;
import uk.codery.jclaim.storage.mongo.MongoEntityStorage;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared MongoDB infrastructure for the adapter test suite. Defaults to a
 * Testcontainers-managed {@code mongo:7} instance; when the environment
 * variable {@code JCLAIM_TEST_MONGO_URI} is set, that pre-existing MongoDB
 * is used instead. Each call to {@link #freshStorage()} hands back a brand
 * new collection so individual tests see an empty namespace.
 */
public final class MongoTestSupport {

    private static final String ENV_URI = "JCLAIM_TEST_MONGO_URI";

    private static volatile boolean initialised;
    private static MongoClient client;
    private static MongoDatabase database;
    private static MongoDBContainer container;

    private static final String COLLECTION_RUN_ID = Long.toHexString(System.nanoTime());
    private static final AtomicInteger COLLECTION_COUNTER = new AtomicInteger();

    private MongoTestSupport() {
    }

    public static MongoEntityStorage freshStorage() {
        ensureInitialised();
        String name = "entities_" + COLLECTION_RUN_ID + "_" + COLLECTION_COUNTER.incrementAndGet();
        MongoCollection<Document> collection = database.getCollection(name);
        return MongoEntityStorage.create(collection);
    }

    public static MongoCollection<Document> newCollection() {
        ensureInitialised();
        String name = "entities_" + COLLECTION_RUN_ID + "_" + COLLECTION_COUNTER.incrementAndGet();
        return database.getCollection(name);
    }

    private static synchronized void ensureInitialised() {
        if (initialised) {
            return;
        }
        String envUri = System.getenv(ENV_URI);
        String uri;
        if (envUri != null && !envUri.isBlank()) {
            uri = envUri;
        } else {
            container = new MongoDBContainer("mongo:7");
            container.start();
            Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "jclaim-mongo-stop"));
            uri = container.getReplicaSetUrl();
        }
        client = MongoClients.create(uri);
        database = client.getDatabase("jclaim_test");
        Runtime.getRuntime().addShutdownHook(new Thread(client::close, "jclaim-mongo-close"));
        initialised = true;
    }
}
