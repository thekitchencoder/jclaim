package uk.codery.jclaim.spring.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
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
 *
 * <h2>Post-processor ordering</h2>
 * This registrar scans the registry for {@code jclaimEntityStorage_<type>} bean
 * definitions and registers one indicator per match. Those definitions are
 * created by {@link EntityTypeResolverRegistrar}; if this registrar ran first it
 * would find none and silently register ZERO indicators (no error). To make that
 * dependency explicit, both registrars implement {@link PriorityOrdered} and this
 * one's {@link #ORDER} is strictly higher (runs later) than the resolver
 * registrar's {@link EntityTypeResolverRegistrar#ORDER}, so the storage
 * definitions always exist by the time this scan runs.
 */
public class PerTypeHealthIndicatorRegistrar
        implements BeanDefinitionRegistryPostProcessor, PriorityOrdered {

    private static final Logger log =
            LoggerFactory.getLogger(PerTypeHealthIndicatorRegistrar.class);

    /**
     * Order value — strictly higher (runs later) than
     * {@link EntityTypeResolverRegistrar#ORDER}, so the per-type storage bean
     * definitions this registrar scans for already exist.
     */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 50;

    /** Bean-name prefix for a per-type health indicator; the bare type key is appended. */
    static final String INDICATOR_PREFIX = "jclaimHealthIndicator_";

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
            throws BeansException {
        if (!(registry instanceof ConfigurableListableBeanFactory beanFactory)) {
            log.warn("Bean registry is not a ConfigurableListableBeanFactory ({}); "
                            + "skipping per-type health indicator registration.",
                    registry.getClass().getName());
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
