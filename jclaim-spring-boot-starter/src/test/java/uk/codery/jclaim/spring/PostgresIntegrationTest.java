package uk.codery.jclaim.spring;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.support.PostgresDockerCondition;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.postgres.PostgresEntityStorage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PostgresDockerCondition.class)
@Testcontainers
@SpringBootTest(classes = {
        JclaimAutoConfiguration.class,
        PostgresIntegrationTest.PgConfig.class
})
class PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("jclaim.storage.type", () -> "postgres");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PgConfig {
        @Bean
        DataSource dataSource() {
            org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
            ds.setUrl(PG.getJdbcUrl());
            ds.setUser(PG.getUsername());
            ds.setPassword(PG.getPassword());
            return ds;
        }
    }

    @Autowired EntityResolver resolver;
    @Autowired EntityStorage storage;

    @Test
    void mintsAndMatchesAgainstRealPostgres() {
        assertThat(storage).isInstanceOf(PostgresEntityStorage.class);

        Claim c = new Claim(
                SourceSystem.of("crm"), "u-1",
                List.of(MatchingAttribute.of("email", "alice@example.com")));
        ResolutionResult first = resolver.resolveOrMint(c);
        ResolutionResult second = resolver.resolveOrMint(c);

        assertThat(first).isInstanceOf(ResolutionResult.Minted.class);
        assertThat(second).isInstanceOf(ResolutionResult.Matched.class);
    }
}
