package uk.codery.jclaim.spring.storage;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.codery.jclaim.spring.JclaimProperties;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.postgres.PostgresEntityStorage;

/**
 * Wires {@link PostgresEntityStorage} when the Postgres adapter is on the
 * classpath and a {@link DataSource} bean is available. The starter does
 * not create the {@code DataSource} itself — that is the responsibility
 * of {@code spring-boot-starter-jdbc} or equivalent.
 *
 * <p>Two sibling nested configurations cover the wiring path — one for the
 * default {@code auto} mode and one for the explicit {@code postgres} mode —
 * so the same body can be reused without an {@code AnyNestedCondition}. A
 * third nested configuration covers the explicit-postgres-without-data-source
 * case and fails startup with a clear message.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({PostgresEntityStorage.class, DataSource.class})
public class PostgresStorageConfiguration {

    private static EntityStorage buildStorage(
            DataSource dataSource, JclaimProperties properties) {
        return PostgresEntityStorage.builder(dataSource)
                .applySchema(properties.storage().postgres().applySchema())
                .build();
    }

    /** Wiring path active when {@code jclaim.storage.type} is unset or {@code auto}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(EntityStorage.class)
    @ConditionalOnProperty(prefix = "jclaim.storage", name = "type",
            havingValue = "auto", matchIfMissing = true)
    static class AutoWiring {

        @Bean
        EntityStorage jclaimPostgresEntityStorage(
                DataSource dataSource, JclaimProperties properties) {
            return buildStorage(dataSource, properties);
        }
    }

    /** Wiring path active when {@code jclaim.storage.type=postgres} and a DataSource bean exists. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(EntityStorage.class)
    @ConditionalOnProperty(prefix = "jclaim.storage", name = "type", havingValue = "postgres")
    static class ExplicitWiring {

        @Bean
        EntityStorage jclaimPostgresEntityStorage(
                DataSource dataSource, JclaimProperties properties) {
            return buildStorage(dataSource, properties);
        }
    }

    /**
     * Fail-fast path: {@code jclaim.storage.type=postgres} is set explicitly but no
     * {@link DataSource} bean is available. Gated on {@code @ConditionalOnMissingBean(DataSource.class)}
     * so this branch is mutually exclusive with {@link ExplicitWiring}; otherwise
     * Spring would consider both sibling configs eligible and could instantiate this
     * fail-fast bean even when a DataSource is in fact present.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean({EntityStorage.class, DataSource.class})
    @ConditionalOnProperty(prefix = "jclaim.storage", name = "type", havingValue = "postgres")
    static class RequiredFailFast {

        @Bean
        EntityStorage jclaimPostgresStorageMissingDataSource(
                ObjectProvider<DataSource> dataSources) {
            throw new IllegalStateException(
                    "jclaim.storage.type=postgres requires a DataSource bean. "
                            + "Add spring-boot-starter-jdbc (or define one).");
        }
    }
}
