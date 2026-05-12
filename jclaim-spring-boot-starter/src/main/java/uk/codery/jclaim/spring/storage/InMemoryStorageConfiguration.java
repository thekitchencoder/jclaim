package uk.codery.jclaim.spring.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

/**
 * Last-resort storage configuration. Registers an {@link InMemoryEntityStorage}
 * whenever no other {@link EntityStorage} bean is present in the context.
 * The Mongo and Postgres storage configurations run earlier (declared
 * via {@code @AutoConfigureBefore} in later commits) and provide their own
 * beans when their classpath + bean prerequisites are met.
 */
@Configuration(proxyBeanMethods = false)
public class InMemoryStorageConfiguration {

    @Bean
    @ConditionalOnMissingBean(EntityStorage.class)
    public EntityStorage jclaimInMemoryEntityStorage() {
        return new InMemoryEntityStorage();
    }
}
