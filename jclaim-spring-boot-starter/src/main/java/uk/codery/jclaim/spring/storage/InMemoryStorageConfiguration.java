package uk.codery.jclaim.spring.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

/**
 * Last-resort storage configuration. Registers an {@link InMemoryEntityStorage}
 * when no other {@link EntityStorage} bean is present in the context. The
 * Mongo and Postgres storage configurations are imported ahead of this one
 * by {@code JclaimAutoConfiguration}; once they register their own
 * {@link EntityStorage} beans (Tasks 5 + 6), this fallback steps back via
 * {@code @ConditionalOnMissingBean(EntityStorage.class)}.
 */
@Configuration(proxyBeanMethods = false)
public class InMemoryStorageConfiguration {

    @Bean
    @ConditionalOnMissingBean(EntityStorage.class)
    public EntityStorage jclaimInMemoryEntityStorage() {
        return new InMemoryEntityStorage();
    }
}
