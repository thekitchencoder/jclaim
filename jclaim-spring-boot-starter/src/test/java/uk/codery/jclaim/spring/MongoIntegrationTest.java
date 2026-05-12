package uk.codery.jclaim.spring;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.support.MongoDockerCondition;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.mongo.MongoEntityStorage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MongoDockerCondition.class)
@Testcontainers
@SpringBootTest(classes = {
        JclaimAutoConfiguration.class,
        MongoIntegrationTest.MongoConfig.class
})
class MongoIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("jclaim.storage.type", () -> "mongo");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MongoConfig {
        @Bean
        MongoClient mongoClient() {
            return MongoClients.create(MONGO.getReplicaSetUrl());
        }
    }

    @Autowired EntityResolver resolver;
    @Autowired EntityStorage storage;

    @Test
    void mintsAndMatchesAgainstRealMongo() {
        assertThat(storage).isInstanceOf(MongoEntityStorage.class);

        Claim c = new Claim(
                SourceSystem.of("crm"), "u-1",
                List.of(MatchingAttribute.of("email", "alice@example.com")));
        ResolutionResult first = resolver.resolveOrMint(c);
        ResolutionResult second = resolver.resolveOrMint(c);

        assertThat(first).isInstanceOf(ResolutionResult.Minted.class);
        assertThat(second).isInstanceOf(ResolutionResult.Matched.class);
    }
}
