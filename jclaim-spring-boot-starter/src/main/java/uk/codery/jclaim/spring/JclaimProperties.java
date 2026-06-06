package uk.codery.jclaim.spring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables exposed by the JClaim Spring Boot starter under the {@code jclaim}
 * property namespace. Covers the URN namespace and type, the human-id template,
 * storage selection and per-adapter settings, match-sink wiring, and the optional
 * metrics and health indicators. Defaults yield a working in-memory setup with no
 * required overrides.
 */
@ConfigurationProperties("jclaim")
public class JclaimProperties {

    private Urn urn = new Urn();
    private HumanId humanId = new HumanId();
    private Storage storage = new Storage();
    private MatchSink matchSink = new MatchSink();
    private Matching matching = new Matching();
    private Metrics metrics = new Metrics();
    private Health health = new Health();

    /**
     * Per-entity-type definitions, keyed by entity type (the URN {@code type}
     * segment). When non-empty the application runs in multi-type mode and the
     * single (default) top-level resolver is suppressed (see Phase 5). The
     * top-level keys serve as inherited defaults for each entry.
     */
    private Map<String, EntityType> entityTypes = new LinkedHashMap<>();

    /** Factory used as the {@code orElseGet} fallback when binding finds no properties. */
    public static JclaimProperties defaults() {
        return new JclaimProperties();
    }

    public Urn urn() {
        return urn;
    }

    public void setUrn(Urn urn) {
        this.urn = urn;
    }

    public HumanId humanId() {
        return humanId;
    }

    public void setHumanId(HumanId humanId) {
        this.humanId = humanId;
    }

    public Storage storage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public MatchSink matchSink() {
        return matchSink;
    }

    public void setMatchSink(MatchSink matchSink) {
        this.matchSink = matchSink;
    }

    public Matching matching() {
        return matching;
    }

    public void setMatching(Matching matching) {
        this.matching = matching;
    }

    public Metrics metrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    public Health health() {
        return health;
    }

    public void setHealth(Health health) {
        this.health = health;
    }

    public Map<String, EntityType> entityTypes() {
        return entityTypes;
    }

    public void setEntityTypes(Map<String, EntityType> entityTypes) {
        this.entityTypes = entityTypes;
    }

    /** Selects which {@code EntityStorage} adapter the starter wires. */
    public enum StorageType {
        AUTO, IN_MEMORY, MONGO, POSTGRES
    }

    /** Selects how {@code MatchEvent} stewardship events are delivered. */
    public enum MatchSinkType {
        SPRING_EVENTS, LOGGING, NOOP
    }

    /** URN namespace + type: urn:&lt;namespace&gt;:&lt;type&gt;:&lt;uuid&gt;. */
    public static class Urn {
        private String namespace = "codery";
        private String type = "entity";

        public String namespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String type() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    /**
     * Human-friendly lookup ID format. The template is opt-in: when absent
     * (null or blank) this entity type mints no humanId. Set a non-blank
     * template (e.g. {@code ????-????-?}) to opt in; a malformed non-blank
     * template fails startup via eager validation.
     */
    public static class HumanId {
        /** Display template; null/blank means no humanId is minted. */
        private String template;

        public String template() {
            return template;
        }

        public void setTemplate(String template) {
            this.template = template;
        }
    }

    /** Storage adapter selection and per-adapter settings. */
    public static class Storage {
        private StorageType type = StorageType.AUTO;
        private Mongo mongo = new Mongo();
        private Postgres postgres = new Postgres();

        public StorageType type() {
            return type;
        }

        public void setType(StorageType type) {
            this.type = type;
        }

        public Mongo mongo() {
            return mongo;
        }

        public void setMongo(Mongo mongo) {
            this.mongo = mongo;
        }

        public Postgres postgres() {
            return postgres;
        }

        public void setPostgres(Postgres postgres) {
            this.postgres = postgres;
        }
    }

    /** MongoDB adapter settings. */
    public static class Mongo {
        private String database = "jclaim";
        private String collectionName = "jclaim_entities";
        private boolean createIndexes = true;

        public String database() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String collectionName() {
            return collectionName;
        }

        public void setCollectionName(String collectionName) {
            this.collectionName = collectionName;
        }

        public boolean createIndexes() {
            return createIndexes;
        }

        public void setCreateIndexes(boolean createIndexes) {
            this.createIndexes = createIndexes;
        }
    }

    /** PostgreSQL adapter settings. */
    public static class Postgres {
        private boolean applySchema = true;

        public boolean applySchema() {
            return applySchema;
        }

        public void setApplySchema(boolean applySchema) {
            this.applySchema = applySchema;
        }
    }

    /** Match-event sink wiring. */
    public static class MatchSink {
        private MatchSinkType type = MatchSinkType.SPRING_EVENTS;

        public MatchSinkType type() {
            return type;
        }

        public void setType(MatchSinkType type) {
            this.type = type;
        }
    }

    /**
     * Matching-policy configuration. When {@code spec} is set the starter builds
     * a jspec-backed {@code MatchingPolicy} from the named classpath resource
     * (requiring {@code jclaim-matching-jspec} on the classpath); otherwise the
     * alias-only default policy is used.
     */
    public static class Matching {
        private String spec;
        private int maxCandidates = 100;
        private List<String> blockingKeys = new ArrayList<>();

        /** Classpath path to the jspec matching spec (e.g. {@code matching/policy.yaml}); null/blank means alias-only. */
        public String spec() {
            return spec;
        }

        public void setSpec(String spec) {
            this.spec = spec;
        }

        public int maxCandidates() {
            return maxCandidates;
        }

        public void setMaxCandidates(int maxCandidates) {
            this.maxCandidates = maxCandidates;
        }

        /**
         * Blocking-key attribute names for the jspec-backed policy. Empty (the
         * default) blocks on every attribute. Only meaningful when {@code spec}
         * is set; ignored (with a WARN) for the alias-only default.
         */
        public List<String> blockingKeys() {
            return blockingKeys;
        }

        public void setBlockingKeys(List<String> blockingKeys) {
            this.blockingKeys = blockingKeys;
        }
    }

    /** Micrometer metrics toggle. */
    public static class Metrics {
        private boolean enabled = true;

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * One entity-type definition under {@code jclaim.entity-types.<type>}.
     * The map key is the entity type (the URN {@code type} segment), so this
     * class carries no {@code type} field. The top-level {@code jclaim.*} keys
     * act as inherited defaults for any value omitted here.
     */
    public static class EntityType {
        /**
         * Per-type URN overrides. {@code namespace} overrides the inherited
         * top-level namespace when set; {@code type} is normally supplied by the
         * map key and left unset (a non-null value disagreeing with the key fails
         * fast at startup). Fields are nullable so "unset" (→ inherit) is distinct
         * from any concrete value, including one equal to a default.
         */
        private EntityTypeUrn urn = new EntityTypeUrn();
        private HumanId humanId = new HumanId();
        private EntityTypeMatching matching = new EntityTypeMatching();
        private EntityTypeStorage storage = new EntityTypeStorage();

        public EntityTypeUrn urn() {
            return urn;
        }

        public void setUrn(EntityTypeUrn urn) {
            this.urn = urn;
        }

        public HumanId humanId() {
            return humanId;
        }

        public void setHumanId(HumanId humanId) {
            this.humanId = humanId;
        }

        public EntityTypeMatching matching() {
            return matching;
        }

        public void setMatching(EntityTypeMatching matching) {
            this.matching = matching;
        }

        public EntityTypeStorage storage() {
            return storage;
        }

        public void setStorage(EntityTypeStorage storage) {
            this.storage = storage;
        }
    }

    /**
     * Per-type URN overrides. All fields nullable: {@code null} means "not set"
     * (inherit the top-level value), distinct from any concrete value. Reusing the
     * top-level {@link Urn} here would be wrong — its non-null defaults
     * ({@code codery}/{@code entity}) make an explicit value equal to the default
     * indistinguishable from omission.
     */
    public static class EntityTypeUrn {
        private String namespace;
        private String type;

        public String namespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String type() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    /**
     * Per-type matching overrides. {@code maxCandidates} is nullable so {@code null}
     * (inherit the top-level value) is distinct from an explicit value — including
     * one equal to the default. {@code spec} is per-type only (never inherited).
     */
    public static class EntityTypeMatching {
        private String spec;
        private Integer maxCandidates;
        private List<String> blockingKeys = new ArrayList<>();

        /** Classpath path to the jspec matching spec; null/blank means alias-only. Per-type only. */
        public String spec() {
            return spec;
        }

        public void setSpec(String spec) {
            this.spec = spec;
        }

        /**
         * Per-type candidate-pool cap. {@code null} (unset) inherits the top-level
         * value; any non-null value overrides. Being a nullable {@code Integer},
         * there is no blank form to consider (unlike the {@code String} namespace
         * override, where blank is also treated as "inherit"); a non-positive value
         * is rejected by the resolver builder.
         */
        public Integer maxCandidates() {
            return maxCandidates;
        }

        public void setMaxCandidates(Integer maxCandidates) {
            this.maxCandidates = maxCandidates;
        }

        /**
         * Per-type blocking-key attribute names. Per-type only (never inherited),
         * mirroring {@code spec}: blocking keys belong to a specific spec. Empty
         * (the default) blocks on every attribute.
         */
        public List<String> blockingKeys() {
            return blockingKeys;
        }

        public void setBlockingKeys(List<String> blockingKeys) {
            this.blockingKeys = blockingKeys;
        }
    }

    /**
     * Per-entity-type storage scoping. Lets a single application route each
     * entity type to its own schema / collection and its own connection bean
     * (DataSource or MongoClient) so types can live in separate stores.
     */
    public static class EntityTypeStorage {
        /** Per-type Postgres schema (scope-name override). */
        private String schema;
        /** Per-type Mongo collection name (scope-name override). */
        private String collectionName;
        /** Bean name of the per-type JDBC {@code DataSource}. */
        private String datasource;
        /** Bean name of the per-type {@code MongoClient}. */
        private String mongoClient;

        public String schema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String collectionName() {
            return collectionName;
        }

        public void setCollectionName(String collectionName) {
            this.collectionName = collectionName;
        }

        public String datasource() {
            return datasource;
        }

        public void setDatasource(String datasource) {
            this.datasource = datasource;
        }

        public String mongoClient() {
            return mongoClient;
        }

        public void setMongoClient(String mongoClient) {
            this.mongoClient = mongoClient;
        }
    }

    /** Spring Boot Actuator health indicator toggle. */
    public static class Health {
        private boolean enabled = true;

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
