package uk.codery.jclaim.spring;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
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
// Load JclaimAutoConfiguration via @ImportAutoConfiguration (not as a plain
// component in `classes`) so it is processed with auto-configuration ordering —
// i.e. AFTER MongoConfig's MongoClient bean is registered. The storage wiring
// uses @ConditionalOnBean(MongoClient), which is only reliable inside an
// auto-configuration evaluated after user beans; listing it in `classes` made
// the condition order-sensitive and brittle.
@SpringBootTest(classes = MongoIntegrationTest.MongoConfig.class)
@ImportAutoConfiguration(JclaimAutoConfiguration.class)
class MongoIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("jclaim.storage.type", () -> "mongo");
    }

    @Configuration(proxyBeanMethods = false)
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
