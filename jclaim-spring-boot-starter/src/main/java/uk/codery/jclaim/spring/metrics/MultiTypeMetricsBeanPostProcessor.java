package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.EntityTypeResolverRegistrar;

import java.util.Objects;

/**
 * Decorates each per-type resolver bean ({@code jclaimEntityResolver_<type>})
 * with a type-tagged {@link MeteredEntityResolver} as it is created. Active in
 * multi-type mode only; the single-type metrics path lives in
 * {@link JclaimMetricsAutoConfiguration} and is untouched.
 *
 * <p>The registrar registers the per-type resolver definitions before regular
 * bean creation, so a {@link BeanPostProcessor} is the natural place to wrap
 * them: the bean name carries the entity type ({@link EntityTypeResolverRegistrar#typeOf}),
 * and wrapping at post-process time means the {@link uk.codery.jclaim.resolver.EntityResolvers}
 * facade — which aggregates the beans by name — observes the metered wrappers.
 * The wrapped bean is <em>not</em> marked primary; selection stays via the
 * type qualifier or the facade.
 *
 * <p>The {@link MeterRegistry} is resolved lazily via an {@link ObjectProvider}
 * so this processor never forces the registry's creation; if none is available
 * the resolver is returned undecorated.
 */
public class MultiTypeMetricsBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<MeterRegistry> registryProvider;

    public MultiTypeMetricsBeanPostProcessor(ObjectProvider<MeterRegistry> registryProvider) {
        this.registryProvider = Objects.requireNonNull(registryProvider, "registryProvider");
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof EntityResolver resolver)) {
            return bean;
        }
        String type = EntityTypeResolverRegistrar.typeOf(beanName);
        if (type == null) {
            return bean;
        }
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return bean;
        }
        return new MeteredEntityResolver(resolver, registry, type);
    }
}
