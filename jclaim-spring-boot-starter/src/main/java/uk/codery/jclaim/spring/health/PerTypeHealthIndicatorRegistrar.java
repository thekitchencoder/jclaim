package uk.codery.jclaim.spring.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import uk.codery.jclaim.spring.EntityTypeResolverRegistrar;
import uk.codery.jclaim.storage.EntityStorage;

/**
 * Registers one {@link JclaimHealthIndicator} per entity type in multi-type mode,
 * named {@code jclaimHealthIndicator_<type>} and probing that type's scoped
 * {@code jclaimEntityStorage_<type>} bean. The prefixed name avoids colliding with
 * the single-type {@code jclaimHealthIndicator} (which backs off in multi-type
 * mode) and with any application health contributors.
 *
 * <p>This is a {@link BeanDefinitionRegistryPostProcessor} (registered via a
 * {@code static @Bean} factory) so it runs after the
 * {@link EntityTypeResolverRegistrar} has registered the per-type storage bean
 * definitions but before regular bean creation. Each indicator's instance
 * supplier resolves the shared storage bean lazily, so the resolver and the
 * health probe share one scoped storage instance — schema/index creation runs
 * once per type.
 */
public class PerTypeHealthIndicatorRegistrar
        implements BeanDefinitionRegistryPostProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(PerTypeHealthIndicatorRegistrar.class);

    /** Bean-name prefix for a per-type health indicator; the bare type key is appended. */
    static final String INDICATOR_PREFIX = "jclaimHealthIndicator_";

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
            throws BeansException {
        if (!(registry instanceof ConfigurableListableBeanFactory beanFactory)) {
            return;
        }
        for (String storageBeanName : registry.getBeanDefinitionNames()) {
            String type = EntityTypeResolverRegistrar.storageTypeOf(storageBeanName);
            if (type == null) {
                continue;
            }
            String indicatorName = INDICATOR_PREFIX + type;
            if (registry.containsBeanDefinition(indicatorName)) {
                continue;
            }
            RootBeanDefinition bd = new RootBeanDefinition(JclaimHealthIndicator.class);
            bd.setInstanceSupplier(() -> new JclaimHealthIndicator(
                    beanFactory.getBean(storageBeanName, EntityStorage.class)));
            registry.registerBeanDefinition(indicatorName, bd);
            log.debug("Registered per-type health indicator '{}' over storage '{}'",
                    indicatorName, storageBeanName);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        // No-op: registration happens in postProcessBeanDefinitionRegistry.
    }
}
