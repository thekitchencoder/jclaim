package uk.codery.jclaim.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables exposed by the JClaim Spring Boot starter under the {@code jclaim}
 * property namespace. Covers the URN namespace, storage selection and per-adapter
 * settings, conflict-sink wiring, and the optional metrics and health indicators.
 * Defaults yield a working in-memory setup with no required overrides.
 */
@ConfigurationProperties("jclaim")
public class JclaimProperties {

    private String namespace = "codery";
    private Storage storage = new Storage();
    private ConflictSink conflictSink = new ConflictSink();
    private Metrics metrics = new Metrics();
    private Health health = new Health();

    /** Factory used as the {@code orElseGet} fallback when binding finds no properties. */
    public static JclaimProperties defaults() {
        return new JclaimProperties();
    }

    public String namespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public Storage storage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public ConflictSink conflictSink() {
        return conflictSink;
    }

    public void setConflictSink(ConflictSink conflictSink) {
        this.conflictSink = conflictSink;
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

    /** Selects which {@code EntityStorage} adapter the starter wires. */
    public enum StorageType {
        AUTO, IN_MEMORY, MONGO, POSTGRES
    }

    /** Selects how {@code EntityAttributesConflicted} events are delivered. */
    public enum ConflictSinkType {
        SPRING_EVENT, LOG, NONE
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

    /** Conflict-event sink wiring. */
    public static class ConflictSink {
        private ConflictSinkType type = ConflictSinkType.SPRING_EVENT;

        public ConflictSinkType type() {
            return type;
        }

        public void setType(ConflictSinkType type) {
            this.type = type;
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
