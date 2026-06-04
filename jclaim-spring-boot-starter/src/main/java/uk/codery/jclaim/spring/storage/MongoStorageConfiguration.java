package uk.codery.jclaim.spring.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import uk.codery.jclaim.spring.JclaimProperties;
import uk.codery.jclaim.spring.NoEntityTypesCondition;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.mongo.MongoEntityStorage;

/**
 * Wires {@link MongoEntityStorage} when the Mongo adapter is on the
 * classpath and a {@link MongoClient} bean is available. The starter does
 * not create the {@code MongoClient} itself — that is the responsibility
 * of {@code spring-boot-starter-data-mongodb} or equivalent. A user-supplied
 * {@link MongoCollection} bean takes precedence over the auto-constructed
 * one, and any user-supplied {@link EntityStorage} bean wins over both.
 *
 * <p>Two sibling nested configurations cover the wiring path — one for the
 * default {@code auto} mode and one for the explicit {@code mongo} mode —
 * so the same body can be reused without an {@code AnyNestedCondition}. A
 * third nested configuration covers the explicit-mongo-without-client
 * case and fails startup with a clear message.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({MongoEntityStorage.class, MongoClient.class})
@Conditional(NoEntityTypesCondition.class)
public class MongoStorageConfiguration {

    private static MongoCollection<Document> buildCollection(
            MongoClient client, JclaimProperties properties) {
        return client.getDatabase(properties.storage().mongo().database())
                .getCollection(properties.storage().mongo().collectionName(), Document.class);
    }

    private static EntityStorage buildStorage(
            MongoCollection<Document> collection, JclaimProperties properties) {
        return MongoEntityStorage.builder(collection)
                .createIndexes(properties.storage().mongo().createIndexes())
                .build();
    }

    /** Wiring path active when {@code jclaim.storage.type} is unset or {@code auto}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(MongoClient.class)
    @ConditionalOnMissingBean(EntityStorage.class)
    @ConditionalOnProperty(prefix = "jclaim.storage", name = "type",
            havingValue = "auto", matchIfMissing = true)
    static class AutoWiring {

        @Bean
        @ConditionalOnMissingBean
        MongoCollection<Document> jclaimEntitiesCollection(
                MongoClient client, JclaimProperties properties) {
            return buildCollection(client, properties);
        }

        @Bean
        EntityStorage jclaimMongoEntityStorage(
                MongoCollection<Document> collection, JclaimProperties properties) {
            return buildStorage(collection, properties);
        }
    }

    /** Wiring path active when {@code jclaim.storage.type=mongo} and a client bean exists. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(MongoClient.class)
    @ConditionalOnMissingBean(EntityStorage.class)
    @ConditionalOnProperty(prefix = "jclaim.storage", name = "type", havingValue = "mongo")
    static class ExplicitWiring {

        @Bean
        @ConditionalOnMissingBean
        MongoCollection<Document> jclaimEntitiesCollection(
                MongoClient client, JclaimProperties properties) {
            return buildCollection(client, properties);
        }

        @Bean
        EntityStorage jclaimMongoEntityStorage(
                MongoCollection<Document> collection, JclaimProperties properties) {
            return buildStorage(collection, properties);
        }
    }

    /**
     * Fail-fast path: {@code jclaim.storage.type=mongo} is set explicitly but no
     * {@link MongoClient} bean is available. Gated on {@code @ConditionalOnMissingBean(MongoClient.class)}
     * so this branch is mutually exclusive with {@link ExplicitWiring}; otherwise
     * Spring would consider both sibling configs eligible and could instantiate this
     * fail-fast bean even when a MongoClient is in fact present.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean({EntityStorage.class, MongoClient.class})
    @ConditionalOnProperty(prefix = "jclaim.storage", name = "type", havingValue = "mongo")
    static class RequiredFailFast {

        @Bean
        EntityStorage jclaimMongoStorageMissingClient(
                ObjectProvider<MongoClient> clients) {
            throw new IllegalStateException(
                    "jclaim.storage.type=mongo requires a MongoClient bean. "
                            + "Add spring-boot-starter-data-mongodb (or define one).");
        }
    }
}
