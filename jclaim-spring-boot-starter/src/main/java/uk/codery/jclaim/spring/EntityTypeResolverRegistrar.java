package uk.codery.jclaim.spring;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.util.ClassUtils;

import com.mongodb.client.MongoClient;

import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.matching.MatchingPolicy;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.JclaimProperties.EntityType;
import uk.codery.jclaim.spring.JclaimProperties.StorageType;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

/**
 * Registers one {@link EntityResolver} bean per {@code jclaim.entity-types.<type>}
 * entry when the application runs in multi-type mode. Each bean is named
 * {@code jclaimEntityResolver_<type>} and carries a {@link Qualifier} of the bare
 * type key, so a caller can inject a specific resolver with
 * {@code @Qualifier("customer") EntityResolver}.
 *
 * <p>Alongside each resolver the registrar registers the type's scoped
 * {@link EntityStorage} as a separate bean named {@code jclaimEntityStorage_<type>}.
 * The resolver resolves that storage bean rather than building its own, so the
 * resolver and the per-type Actuator health contributor share <em>one</em> scoped
 * storage instance — schema/index creation runs exactly once per type.
 *
 * <p>This is a {@link BeanDefinitionRegistryPostProcessor} (and must be registered
 * via a {@code static @Bean} factory method) so it runs before regular bean
 * factory post-processing. Collaborators — the {@link MatchEventSink}, the
 * connection beans ({@link DataSource} / {@link MongoClient}) — are resolved
 * <em>lazily</em> from the bean factory at resolver-creation time via an instance
 * supplier, never during registration; the registrar only captures the bean
 * factory and reads bound configuration.
 *
 * <h2>Configuration inheritance</h2>
 * Each entry inherits the top-level {@code jclaim.*} keys as defaults:
 * <ul>
 *   <li><b>namespace</b> — inherits the top-level {@code jclaim.urn.namespace}.
 *       A per-type {@code jclaim.entity-types.<type>.urn.namespace} overrides it
 *       only when it differs from the {@code Urn} default ({@code "codery"}).</li>
 *   <li><b>type</b> — always the map key. A per-type {@code urn.type} that is set
 *       to a non-default value disagreeing with the key is a configuration error
 *       and fails startup (the key is authoritative).</li>
 *   <li><b>matching.spec</b> — per-type only; not inherited.</li>
 *   <li><b>matching.max-candidates</b> — inherits the top-level default unless the
 *       entry sets a non-default value.</li>
 *   <li><b>storage kind</b> — app-global ({@code jclaim.storage.type}); per-type
 *       entries only scope the schema/collection and the connection bean.</li>
 * </ul>
 *
 * <h2>Scope-collision detection</h2>
 * Two types whose resolved scope (Postgres schema / Mongo collection) is identical
 * on the same connection bean would silently share a physical store. Where the
 * scope is statically known at registration time the registrar tracks
 * {@code (connection-bean-or-default, kind, scope)} and fails fast on a duplicate.
 */
public class EntityTypeResolverRegistrar
        implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(EntityTypeResolverRegistrar.class);

    /** Bean-name prefix for a per-type resolver; the bare type key is appended. */
    static final String BEAN_PREFIX = "jclaimEntityResolver_";

    /** Bean-name prefix for a per-type {@link EntityStorage}; the bare type key is appended. */
    static final String STORAGE_BEAN_PREFIX = "jclaimEntityStorage_";

    private static final String JSPEC_POLICY_CLASS =
            "uk.codery.jclaim.matching.jspec.JspecMatchingPolicy";

    /** Default namespace baked into {@link JclaimProperties.Urn}. */
    private static final String DEFAULT_NAMESPACE = "codery";
    /** Default URN type baked into {@link JclaimProperties.Urn}. */
    private static final String DEFAULT_TYPE = "entity";
    /** Default max-candidates baked into {@link JclaimProperties.Matching}. */
    private static final int DEFAULT_MAX_CANDIDATES = 100;

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
            throws BeansException {
        JclaimProperties props = Binder.get(environment)
                .bind("jclaim", JclaimProperties.class)
                .orElseGet(JclaimProperties::defaults);

        Map<String, EntityType> entityTypes = props.entityTypes();
        if (entityTypes.isEmpty()) {
            return;
        }

        StorageType kind = resolveStorageKind(registry, props);

        // (connection-bean-or-default + kind + scope) -> first type that claimed it.
        Map<String, String> claimedScopes = new LinkedHashMap<>();

        for (Map.Entry<String, EntityType> e : entityTypes.entrySet()) {
            String type = e.getKey();
            EntityType entry = e.getValue();

            validateTypeKeyAgreement(type, entry);
            reserveScope(claimedScopes, kind, type, entry);

            // Per-type storage bean. One scoped EntityStorage instance per type,
            // shared by the resolver and the per-type health contributor — built
            // once via the instance supplier so schema/index creation runs once.
            String storageBeanName = STORAGE_BEAN_PREFIX + type;
            RootBeanDefinition storageBd = new RootBeanDefinition(EntityStorage.class);
            storageBd.setInstanceSupplier(() -> buildStorage(
                    (ConfigurableListableBeanFactory) registry, kind, type, entry, props));
            storageBd.addQualifier(new AutowireCandidateQualifier(Qualifier.class, type));
            registry.registerBeanDefinition(storageBeanName, storageBd);

            String beanName = BEAN_PREFIX + type;
            RootBeanDefinition bd = new RootBeanDefinition(EntityResolver.class);
            bd.setInstanceSupplier(() -> buildResolver(
                    (ConfigurableListableBeanFactory) registry, type, entry, props, storageBeanName));
            bd.addQualifier(new AutowireCandidateQualifier(Qualifier.class, type));
            registry.registerBeanDefinition(beanName, bd);
            log.debug("Registered per-type resolver bean '{}' (storage '{}') for entity type '{}'",
                    beanName, storageBeanName, type);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        // No-op: all wiring happens in postProcessBeanDefinitionRegistry.
    }

    /**
     * App-global storage-kind decision. An explicit {@code jclaim.storage.type}
     * wins; under {@code AUTO} infer from available connection beans
     * (DataSource → Postgres, MongoClient → Mongo, else in-memory).
     */
    private StorageType resolveStorageKind(BeanDefinitionRegistry registry, JclaimProperties props) {
        StorageType configured = props.storage().type();
        if (configured != StorageType.AUTO) {
            return configured;
        }
        if (registry instanceof ConfigurableListableBeanFactory bf) {
            if (bf.getBeanNamesForType(DataSource.class, false, false).length > 0) {
                return StorageType.POSTGRES;
            }
            if (bf.getBeanNamesForType(MongoClient.class, false, false).length > 0) {
                return StorageType.MONGO;
            }
        }
        return StorageType.IN_MEMORY;
    }

    /**
     * Fail fast when a per-type {@code urn.type} is explicitly set to a non-default
     * value that disagrees with the authoritative map key.
     */
    private void validateTypeKeyAgreement(String type, EntityType entry) {
        String entryType = entry.urn().type();
        if (entryType != null
                && !entryType.equals(DEFAULT_TYPE)
                && !entryType.equals(type)) {
            throw new IllegalStateException(
                    "jclaim.entity-types." + type + ".urn.type='" + entryType
                            + "' conflicts with the map key '" + type + "'. The map key is the "
                            + "authoritative entity type; remove the urn.type override (or set it "
                            + "to '" + type + "').");
        }
    }

    /**
     * Tracks statically-known scopes and fails fast on a collision (two types
     * resolving to the same schema/collection on the same connection bean).
     */
    private void reserveScope(Map<String, String> claimed, StorageType kind, String type, EntityType entry) {
        String connection;
        String scope;
        switch (kind) {
            case POSTGRES -> {
                connection = entry.storage().datasource() != null
                        ? entry.storage().datasource() : "<primary-datasource>";
                scope = entry.storage().schema() != null ? entry.storage().schema() : type;
            }
            case MONGO -> {
                connection = entry.storage().mongoClient() != null
                        ? entry.storage().mongoClient() : "<primary-mongo-client>";
                scope = entry.storage().collectionName() != null
                        ? entry.storage().collectionName() : type;
            }
            default -> {
                // In-memory: each type gets its own fresh InMemoryEntityStorage instance,
                // so instances are never actually shared. This explicit-scope clash check
                // is an intentionally over-strict guard: naming the same schema/collection
                // on two in-memory types signals a copy-paste misconfiguration the author
                // probably meant to catch on a real backend, so we fail fast here too.
                String explicit = entry.storage().schema() != null
                        ? entry.storage().schema()
                        : entry.storage().collectionName();
                if (explicit == null) {
                    return;
                }
                connection = "<in-memory>";
                scope = explicit;
            }
        }
        String key = connection + "::" + kind + "::" + scope;
        String existing = claimed.putIfAbsent(key, type);
        if (existing != null) {
            throw new IllegalStateException(
                    "Entity types '" + existing + "' and '" + type + "' resolve to the same storage "
                            + "scope (" + kind + " scope '" + scope + "' on connection " + connection
                            + "). Each entity type must occupy a distinct schema/collection; set a "
                            + "distinct storage.schema/storage.collection-name (or a different "
                            + "connection bean).");
        }
    }

    /**
     * Builds a resolver for one entity type. Runs lazily at first injection, so all
     * collaborators are resolved from the live bean factory here.
     */
    private EntityResolver buildResolver(
            ConfigurableListableBeanFactory beanFactory,
            String type,
            EntityType entry,
            JclaimProperties props,
            String storageBeanName) {

        MatchEventSink sink = beanFactory.getBean(MatchEventSink.class);
        MatchingPolicy policy = buildMatchingPolicy(type, entry);
        String namespace = resolveNamespace(props, entry);
        int maxCandidates = resolveMaxCandidates(props, entry);
        // Reuse the single scoped storage instance registered as a bean, so the
        // resolver and the per-type health contributor share one store (and
        // schema/index creation happens exactly once).
        EntityStorage storage = beanFactory.getBean(storageBeanName, EntityStorage.class);

        return DefaultEntityResolver.builder(storage)
                .namespace(namespace)
                .entityType(type)
                .humanIdTemplate(entry.humanId().template())
                .matchEventSink(sink)
                .matchingPolicy(policy)
                .maxCandidates(maxCandidates)
                .build();
    }

    /**
     * Per-type matching policy. A configured {@code spec} requires the optional
     * jspec module; its absence is a fail-fast configuration error naming the type.
     * No spec → alias-only. Spec is per-type only and is never inherited.
     */
    private MatchingPolicy buildMatchingPolicy(String type, EntityType entry) {
        String spec = entry.matching().spec();
        if (spec == null || spec.isBlank()) {
            return MatchingPolicy.aliasOnly();
        }
        if (!ClassUtils.isPresent(JSPEC_POLICY_CLASS, getClass().getClassLoader())) {
            throw new IllegalStateException(
                    "jclaim.entity-types." + type + ".matching.spec is set but the "
                            + "jclaim-matching-jspec module is not on the classpath. Add the "
                            + "uk.codery:jclaim-matching-jspec dependency to enable jspec-backed "
                            + "matching, or unset the spec to use the alias-only default policy.");
        }
        return uk.codery.jclaim.matching.jspec.JspecMatchingPolicy.fromResource(normalise(spec));
    }

    /**
     * Namespace inherits the top-level default; a per-type {@code urn.namespace}
     * overrides only when it differs from the {@code Urn} default ({@code "codery"}).
     *
     * <p><b>Heuristic limitation:</b> override detection compares the bound per-type
     * value against the {@code Urn} record default rather than tracking whether the
     * key was explicitly present in the environment. So a per-type entry that
     * explicitly sets {@code urn.namespace=codery} is indistinguishable from one
     * that omits it, and will inherit the top-level value instead of pinning
     * {@code codery}. Resolving this precisely would require nullable property types
     * on the shared {@code Urn} class, which is out of scope here (it touches the
     * single-type path). The same sentinel approximation applies to
     * {@link #resolveMaxCandidates}.
     */
    private String resolveNamespace(JclaimProperties props, EntityType entry) {
        String entryNs = entry.urn().namespace();
        if (entryNs != null && !entryNs.equals(DEFAULT_NAMESPACE)) {
            return entryNs;
        }
        return props.urn().namespace();
    }

    /**
     * Max-candidates inherits the top-level default; a per-type
     * {@code matching.max-candidates} overrides only when it differs from the
     * {@code Matching} default ({@value #DEFAULT_MAX_CANDIDATES}).
     *
     * <p><b>Heuristic limitation (symmetric with {@link #resolveNamespace}):</b>
     * override detection compares the bound per-type value against the record
     * default rather than tracking explicit presence in the environment. So if the
     * top-level value is, say, {@code 50} and a type explicitly sets
     * {@code max-candidates=100} (the default), the per-type {@code 100} is treated
     * as "not overridden" and the type inherits {@code 50} instead of pinning
     * {@code 100}. Resolving this precisely would require a nullable
     * {@code maxCandidates} on the shared {@code Matching} class, which is out of
     * scope here (it touches the single-type path).
     */
    private int resolveMaxCandidates(JclaimProperties props, EntityType entry) {
        int entryMax = entry.matching().maxCandidates();
        if (entryMax != DEFAULT_MAX_CANDIDATES) {
            return entryMax;
        }
        return props.matching().maxCandidates();
    }

    private EntityStorage buildStorage(
            ConfigurableListableBeanFactory beanFactory,
            StorageType kind,
            String type,
            EntityType entry,
            JclaimProperties props) {
        return switch (kind) {
            // AUTO is already resolved to a concrete kind by resolveStorageKind, so it
            // never reaches here; it shares the in-memory arm only to keep the switch
            // exhaustive over the StorageType enum.
            case IN_MEMORY, AUTO -> new InMemoryEntityStorage();
            case POSTGRES -> buildPostgresStorage(beanFactory, type, entry, props);
            case MONGO -> buildMongoStorage(beanFactory, type, entry, props);
        };
    }

    private EntityStorage buildPostgresStorage(
            ConfigurableListableBeanFactory beanFactory,
            String type,
            EntityType entry,
            JclaimProperties props) {
        DataSource dataSource = resolveConnection(
                beanFactory, DataSource.class, entry.storage().datasource(), type, "DataSource");
        String schema = entry.storage().schema() != null ? entry.storage().schema() : type;
        return uk.codery.jclaim.storage.postgres.PostgresEntityStorage.builder(dataSource)
                .schema(schema)
                .applySchema(props.storage().postgres().applySchema())
                .build();
    }

    private EntityStorage buildMongoStorage(
            ConfigurableListableBeanFactory beanFactory,
            String type,
            EntityType entry,
            JclaimProperties props) {
        MongoClient client = resolveConnection(
                beanFactory, MongoClient.class, entry.storage().mongoClient(), type, "MongoClient");
        String collection = entry.storage().collectionName() != null
                ? entry.storage().collectionName() : type;
        return uk.codery.jclaim.storage.mongo.MongoEntityStorage.builder(
                        client.getDatabase(props.storage().mongo().database())
                                .getCollection(collection, Document.class))
                .createIndexes(props.storage().mongo().createIndexes())
                .build();
    }

    /**
     * Resolves a connection bean by explicit name (per-type override) or by type
     * (the primary bean). A missing named bean is a fail-fast error naming both the
     * entity type and the missing bean.
     */
    private <T> T resolveConnection(
            ConfigurableListableBeanFactory beanFactory,
            Class<T> beanType,
            String beanName,
            String type,
            String label) {
        if (beanName != null) {
            try {
                return beanFactory.getBean(beanName, beanType);
            } catch (NoSuchBeanDefinitionException ex) {
                throw new IllegalStateException(
                        "Entity type '" + type + "' references " + label + " bean '" + beanName
                                + "' (via jclaim.entity-types." + type + ".storage), but no such bean "
                                + "exists.", ex);
            }
        }
        try {
            return beanFactory.getBean(beanType);
        } catch (NoSuchBeanDefinitionException ex) {
            throw new IllegalStateException(
                    "Entity type '" + type + "' requires a " + label + " bean for "
                            + "the configured storage kind, but none is available.", ex);
        }
    }

    /**
     * Accepts both Spring-style {@code classpath:matching/x.yaml} and bare
     * {@code matching/x.yaml}; {@code JspecMatchingPolicy.fromResource} needs an
     * absolute (leading-slash) classpath path.
     */
    private static String normalise(String spec) {
        String path = spec.startsWith("classpath:") ? spec.substring("classpath:".length()) : spec;
        return path.startsWith("/") ? path : "/" + path;
    }

    /** Returns the bare type key for a per-type resolver bean name, or {@code null}. */
    public static String typeOf(String beanName) {
        return beanName.startsWith(BEAN_PREFIX) ? beanName.substring(BEAN_PREFIX.length()) : null;
    }

    /** Returns the bare type key for a per-type storage bean name, or {@code null}. */
    public static String storageTypeOf(String beanName) {
        return beanName.startsWith(STORAGE_BEAN_PREFIX)
                ? beanName.substring(STORAGE_BEAN_PREFIX.length()) : null;
    }
}
