# jclaim-spring-boot-starter Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add `jclaim-spring-boot-starter` as a fourth Maven module that auto-configures `EntityResolver`, picks the right `EntityStorage` adapter from the classpath, bridges `ConflictEventSink` to Spring's `ApplicationEventPublisher`, and exposes optional Actuator health + Micrometer metrics — all without leaking Spring into the existing three modules.

**Architecture:** A small Spring Boot 3.x `@AutoConfiguration` module that consumes the existing core + adapter modules unchanged. Configuration via `@ConfigurationProperties("jclaim")`. Conditional wiring picks the storage adapter by classpath presence plus property override. Conflict events flow through a `SpringEventConflictSink` that wraps the existing `EntityAttributesConflicted` record in a `JclaimConflictEvent extends ApplicationEvent`. All beans `@ConditionalOnMissingBean` so users can override any layer.

**Tech Stack:**
- Spring Boot 3.5.x (Java 21, modern `META-INF/spring/...AutoConfiguration.imports` registration)
- `spring-boot-autoconfigure`, `spring-boot-actuator` (optional), `micrometer-core` (optional)
- Test: `spring-boot-test`, `ApplicationContextRunner`, Testcontainers (Mongo + Postgres)

**Locked decisions (confirmed before planning):**
- Root package: `uk.codery.jclaim.spring`
- Property prefix: `jclaim`, with adapter-aligned keys (e.g. `storage.postgres.apply-schema` not `schema-init`, plus `storage.mongo.database` for the database name)
- Exposed beans: `EntityResolver`, `EntityStorage`, `ConflictEventSink` — all `@ConditionalOnMissingBean`
- Conflict event shape: `JclaimConflictEvent extends ApplicationEvent` wrapping the existing `EntityAttributesConflicted` payload

**Core invariant (verify at start and end):** `jclaim-core/pom.xml` must contain **zero** Spring dependencies. The starter depends on core, never the reverse.

---

## Task 0: Verify core stays Spring-independent (pre-flight)

**Files:**
- Inspect: `jclaim-core/pom.xml`, `jclaim-storage-mongo/pom.xml`, `jclaim-storage-postgres/pom.xml`

**Step 1: Confirm no Spring deps in core or adapters**

Run: `grep -RIn "springframework\|spring-boot" jclaim-core jclaim-storage-mongo jclaim-storage-postgres pom.xml`
Expected: no matches (or only matches inside comments — none should be runtime/compile-scope deps).

**Step 2: Snapshot the parent POM modules list**

Run: `grep -n "<module>" pom.xml`
Expected: three modules listed (`jclaim-core`, `jclaim-storage-postgres`, `jclaim-storage-mongo`). The starter will be added in Task 1.

---

## Task 1: Module skeleton + parent POM registration

**Files:**
- Create: `jclaim-spring-boot-starter/pom.xml`
- Create: `jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/package-info.java`
- Create: `jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/JclaimAutoConfiguration.java` (empty `@AutoConfiguration` shell)
- Modify: `pom.xml` (add `<spring-boot.version>` to `<properties>`, register Spring Boot BOM in `<dependencyManagement>`, register module)

**Step 1: Add Spring Boot version + BOM to parent POM `<properties>` and `<dependencyManagement>`**

In `pom.xml` `<properties>` block, add:
```xml
<spring-boot.version>3.5.5</spring-boot.version>
<micrometer.version>1.13.6</micrometer.version>
```

In `<dependencyManagement><dependencies>`, add the Spring Boot dependencies BOM import (handles spring-boot-autoconfigure, actuator, test, etc. transitively) and the Micrometer BOM:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-dependencies</artifactId>
    <version>${spring-boot.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-bom</artifactId>
    <version>${micrometer.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

Note: at execution time, verify these versions are current with `mvn versions:display-dependency-updates -pl jclaim-spring-boot-starter` and bump if stale.

**Step 2: Register the new module**

In `pom.xml`, append to `<modules>`:
```xml
<module>jclaim-spring-boot-starter</module>
```

**Step 3: Create `jclaim-spring-boot-starter/pom.xml`**

Model on `jclaim-storage-postgres/pom.xml`. Required deps:
- `uk.codery:jclaim-core` (compile)
- `org.springframework.boot:spring-boot-autoconfigure` (compile, version managed by BOM)
- `org.springframework.boot:spring-boot-actuator` (compile, `<optional>true</optional>`)
- `io.micrometer:micrometer-core` (compile, `<optional>true</optional>`)
- `uk.codery:jclaim-storage-mongo` (compile, `<optional>true</optional>`)
- `uk.codery:jclaim-storage-postgres` (compile, `<optional>true</optional>`)
- `org.slf4j:slf4j-api` (compile)

Test deps:
- `spring-boot-starter-test`
- `spring-boot-starter-actuator` (to load HealthEndpoint in tests)
- `micrometer-core`
- `org.mongodb:mongodb-driver-sync` (for Mongo conditional tests)
- `org.testcontainers:mongodb`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`
- `org.junit.jupiter:junit-jupiter`
- `org.assertj:assertj-core`
- `org.slf4j:slf4j-simple`

The `<build>` block mirrors the postgres pom (compiler/surefire/jacoco/source/javadoc/central plugins). Set `<haltOnFailure>false</haltOnFailure>` on `jacoco-check` because Mongo/Postgres conditional tests need Docker, same pattern as the storage modules.

**Step 4: Create empty `JclaimAutoConfiguration` shell + package-info**

`uk/codery/jclaim/spring/package-info.java`:
```java
/**
 * Spring Boot auto-configuration for JClaim. Detects an {@link
 * uk.codery.jclaim.storage.EntityStorage} adapter on the classpath, wires
 * a {@link uk.codery.jclaim.resolver.EntityResolver} bean, and bridges
 * conflict events to Spring's {@code ApplicationEventPublisher}.
 *
 * <p>The starter never replaces Spring Data MongoDB / JDBC autoconfigurations;
 * it consumes the {@link com.mongodb.client.MongoClient} or {@link
 * javax.sql.DataSource} beans those starters provide.
 */
package uk.codery.jclaim.spring;
```

`uk/codery/jclaim/spring/JclaimAutoConfiguration.java`:
```java
package uk.codery.jclaim.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Top-level auto-configuration for JClaim. Imports nested configurations
 * via {@code AutoConfiguration.imports}; this class is intentionally a
 * minimal hook so users can {@code @ImportAutoConfiguration} it directly.
 */
@AutoConfiguration
public class JclaimAutoConfiguration {
}
```

**Step 5: Verify the reactor builds**

Run: `mvn -q -pl jclaim-spring-boot-starter -am clean install -DskipTests`
Expected: BUILD SUCCESS, four modules built.

**Step 6: Commit**

```bash
git add pom.xml jclaim-spring-boot-starter/
git commit -m "feat(spring-boot-starter): module skeleton

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: `JclaimProperties` configuration class

**Files:**
- Create: `jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/JclaimProperties.java`

**Step 1: Write the test first**

Create `jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/JclaimPropertiesTest.java`:
```java
package uk.codery.jclaim.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JclaimPropertiesTest {

    @Test
    void bindsDefaults() {
        JclaimProperties props = bind(Map.of());
        assertThat(props.namespace()).isEqualTo("codery");
        assertThat(props.storage().type()).isEqualTo(JclaimProperties.StorageType.AUTO);
        assertThat(props.storage().mongo().database()).isEqualTo("jclaim");
        assertThat(props.storage().mongo().collectionName()).isEqualTo("jclaim_entities");
        assertThat(props.storage().mongo().createIndexes()).isTrue();
        assertThat(props.storage().postgres().applySchema()).isTrue();
        assertThat(props.conflictSink().type()).isEqualTo(JclaimProperties.ConflictSinkType.SPRING_EVENT);
        assertThat(props.metrics().enabled()).isTrue();
        assertThat(props.health().enabled()).isTrue();
    }

    @Test
    void bindsOverrides() {
        JclaimProperties props = bind(Map.of(
                "jclaim.namespace", "acme",
                "jclaim.storage.type", "mongo",
                "jclaim.storage.mongo.database", "acme_db",
                "jclaim.storage.mongo.collection-name", "entities",
                "jclaim.storage.postgres.apply-schema", "false",
                "jclaim.conflict-sink.type", "log",
                "jclaim.metrics.enabled", "false",
                "jclaim.health.enabled", "false"
        ));
        assertThat(props.namespace()).isEqualTo("acme");
        assertThat(props.storage().type()).isEqualTo(JclaimProperties.StorageType.MONGO);
        assertThat(props.storage().mongo().database()).isEqualTo("acme_db");
        assertThat(props.storage().mongo().collectionName()).isEqualTo("entities");
        assertThat(props.storage().postgres().applySchema()).isFalse();
        assertThat(props.conflictSink().type()).isEqualTo(JclaimProperties.ConflictSinkType.LOG);
        assertThat(props.metrics().enabled()).isFalse();
        assertThat(props.health().enabled()).isFalse();
    }

    private static JclaimProperties bind(Map<String, String> map) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(map);
        return new Binder(source).bind("jclaim", JclaimProperties.class).orElseGet(JclaimProperties::defaults);
    }
}
```

**Step 2: Run the test, watch it fail (compile error — class doesn't exist)**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimPropertiesTest`
Expected: COMPILATION FAILURE — `JclaimProperties` does not exist.

**Step 3: Implement `JclaimProperties` as a nested record-friendly `@ConfigurationProperties`**

Use a class with nested classes (not records) because Spring Boot 3 deep-binding works most cleanly that way — records work, but mutable nested holders make the `Binder` happiest without `@ConstructorBinding` on every nested level. Pattern:

```java
package uk.codery.jclaim.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jclaim")
public class JclaimProperties {

    private String namespace = "codery";
    private Storage storage = new Storage();
    private ConflictSink conflictSink = new ConflictSink();
    private Metrics metrics = new Metrics();
    private Health health = new Health();

    public enum StorageType { AUTO, IN_MEMORY, MONGO, POSTGRES }
    public enum ConflictSinkType { SPRING_EVENT, LOG, NONE }

    public static JclaimProperties defaults() { return new JclaimProperties(); }

    // getters/setters + the record-style accessor methods used in the test
    public String namespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public Storage storage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }
    public ConflictSink conflictSink() { return conflictSink; }
    public void setConflictSink(ConflictSink conflictSink) { this.conflictSink = conflictSink; }
    public Metrics metrics() { return metrics; }
    public void setMetrics(Metrics metrics) { this.metrics = metrics; }
    public Health health() { return health; }
    public void setHealth(Health health) { this.health = health; }

    public static class Storage {
        private StorageType type = StorageType.AUTO;
        private Mongo mongo = new Mongo();
        private Postgres postgres = new Postgres();
        public StorageType type() { return type; }
        public void setType(StorageType type) { this.type = type; }
        public Mongo mongo() { return mongo; }
        public void setMongo(Mongo mongo) { this.mongo = mongo; }
        public Postgres postgres() { return postgres; }
        public void setPostgres(Postgres postgres) { this.postgres = postgres; }
    }

    public static class Mongo {
        private String database = "jclaim";
        private String collectionName = "jclaim_entities";
        private boolean createIndexes = true;
        public String database() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public String collectionName() { return collectionName; }
        public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
        public boolean createIndexes() { return createIndexes; }
        public void setCreateIndexes(boolean createIndexes) { this.createIndexes = createIndexes; }
    }

    public static class Postgres {
        private boolean applySchema = true;
        public boolean applySchema() { return applySchema; }
        public void setApplySchema(boolean applySchema) { this.applySchema = applySchema; }
    }

    public static class ConflictSink {
        private ConflictSinkType type = ConflictSinkType.SPRING_EVENT;
        public ConflictSinkType type() { return type; }
        public void setType(ConflictSinkType type) { this.type = type; }
    }

    public static class Metrics {
        private boolean enabled = true;
        public boolean enabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Health {
        private boolean enabled = true;
        public boolean enabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
```

**Step 4: Run the test, watch it pass**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimPropertiesTest`
Expected: BUILD SUCCESS, 2 tests pass.

**Step 5: Commit**

```bash
git add jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/JclaimProperties.java \
        jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/JclaimPropertiesTest.java
git commit -m "feat(spring-boot-starter): JclaimProperties with nested storage/sink/observability config

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: Base `JclaimAutoConfiguration` — resolver + default conflict sink + in-memory fallback

**Files:**
- Modify: `jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/JclaimAutoConfiguration.java`
- Create: `jclaim-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/JclaimAutoConfigurationTest.java`

**Step 1: Write the failing test for the no-properties default**

```java
package uk.codery.jclaim.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.codery.jclaim.event.ConflictEventSink;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import static org.assertj.core.api.Assertions.assertThat;

class JclaimAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class));

    @Test
    void defaultsToInMemoryResolverWithSpringEventSink() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(EntityResolver.class);
            assertThat(ctx).hasSingleBean(EntityStorage.class);
            assertThat(ctx.getBean(EntityStorage.class)).isInstanceOf(InMemoryEntityStorage.class);
            assertThat(ctx).hasSingleBean(ConflictEventSink.class);
        });
    }

    @Test
    void userProvidedResolverWins() {
        runner.withUserConfiguration(UserResolverConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(EntityResolver.class);
            assertThat(ctx.getBean(EntityResolver.class)).isSameAs(UserResolverConfig.MARKER);
        });
    }

    static class UserResolverConfig {
        static final EntityResolver MARKER = new EntityResolver() {
            // stub — methods throw, this is only used for identity check
            @Override public uk.codery.jclaim.model.ResolutionResult resolveOrMint(uk.codery.jclaim.model.Claim c) { throw new UnsupportedOperationException(); }
            @Override public uk.codery.jclaim.model.Entity getByUrn(uk.codery.jclaim.model.EntityId u) { throw new UnsupportedOperationException(); }
            @Override public java.util.Optional<uk.codery.jclaim.model.Entity> findByHumanId(String s) { throw new UnsupportedOperationException(); }
            @Override public java.util.Optional<uk.codery.jclaim.model.Entity> findByAlias(uk.codery.jclaim.model.SourceSystem s, String i) { throw new UnsupportedOperationException(); }
            @Override public uk.codery.jclaim.model.Entity addAlias(uk.codery.jclaim.model.EntityId u, uk.codery.jclaim.model.SourceSystem s, String i) { throw new UnsupportedOperationException(); }
            @Override public java.util.Set<uk.codery.jclaim.model.Entity> findCandidates(uk.codery.jclaim.model.Claim c) { throw new UnsupportedOperationException(); }
        };
        @org.springframework.context.annotation.Bean EntityResolver userResolver() { return MARKER; }
    }
}
```

**Step 2: Run the test, watch it fail**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimAutoConfigurationTest`
Expected: FAILURE — no EntityResolver bean.

**Step 3: Implement the base auto-configuration**

Replace `JclaimAutoConfiguration.java`:

```java
package uk.codery.jclaim.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import uk.codery.jclaim.event.ConflictEventSink;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.conflict.LoggingConflictSink;
import uk.codery.jclaim.spring.conflict.SpringEventConflictSink;
import uk.codery.jclaim.spring.storage.InMemoryStorageConfiguration;
import uk.codery.jclaim.spring.storage.MongoStorageConfiguration;
import uk.codery.jclaim.spring.storage.PostgresStorageConfiguration;
import uk.codery.jclaim.storage.EntityStorage;

@AutoConfiguration
@EnableConfigurationProperties(JclaimProperties.class)
@org.springframework.context.annotation.Import({
        PostgresStorageConfiguration.class,
        MongoStorageConfiguration.class,
        InMemoryStorageConfiguration.class
})
public class JclaimAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConflictEventSink jclaimConflictEventSink(
            JclaimProperties properties,
            org.springframework.beans.factory.ObjectProvider<ApplicationEventPublisher> publishers) {
        return switch (properties.conflictSink().type()) {
            case SPRING_EVENT -> new SpringEventConflictSink(
                    publishers.getIfAvailable(() -> { throw new IllegalStateException(
                            "spring-event conflict sink requires an ApplicationEventPublisher"); }));
            case LOG -> new LoggingConflictSink();
            case NONE -> ConflictEventSink.noop();
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public EntityResolver jclaimEntityResolver(
            EntityStorage storage,
            ConflictEventSink conflictSink,
            JclaimProperties properties) {
        return DefaultEntityResolver.builder(storage)
                .namespace(properties.namespace())
                .conflictSink(conflictSink)
                .build();
    }
}
```

For now, stub the three storage configuration classes so the import compiles:

`uk/codery/jclaim/spring/storage/InMemoryStorageConfiguration.java`:
```java
package uk.codery.jclaim.spring.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

/**
 * Last-resort storage configuration. Registers an {@link InMemoryEntityStorage}
 * only if no other {@link EntityStorage} bean is present in the context.
 * The Mongo and Postgres configurations run earlier via {@code @AutoConfigureBefore}
 * and provide their own beans when their classpath + bean prerequisites are met.
 */
@Configuration(proxyBeanMethods = false)
public class InMemoryStorageConfiguration {

    @Bean
    @ConditionalOnMissingBean(EntityStorage.class)
    public EntityStorage jclaimInMemoryEntityStorage() {
        return new InMemoryEntityStorage();
    }
}
```

Empty stubs for Mongo + Postgres configurations (filled in Tasks 5 + 6):
```java
// uk/codery/jclaim/spring/storage/MongoStorageConfiguration.java
package uk.codery.jclaim.spring.storage;
import org.springframework.context.annotation.Configuration;
@Configuration(proxyBeanMethods = false)
public class MongoStorageConfiguration { }

// uk/codery/jclaim/spring/storage/PostgresStorageConfiguration.java
package uk.codery.jclaim.spring.storage;
import org.springframework.context.annotation.Configuration;
@Configuration(proxyBeanMethods = false)
public class PostgresStorageConfiguration { }
```

Stub `SpringEventConflictSink` + `LoggingConflictSink` (full impls land in Task 7):
```java
// uk/codery/jclaim/spring/conflict/SpringEventConflictSink.java
package uk.codery.jclaim.spring.conflict;

import org.springframework.context.ApplicationEventPublisher;
import uk.codery.jclaim.event.ConflictEventSink;
import uk.codery.jclaim.event.EntityAttributesConflicted;

public final class SpringEventConflictSink implements ConflictEventSink {
    private final ApplicationEventPublisher publisher;
    public SpringEventConflictSink(ApplicationEventPublisher publisher) {
        this.publisher = java.util.Objects.requireNonNull(publisher, "publisher");
    }
    @Override public void accept(EntityAttributesConflicted event) {
        publisher.publishEvent(new JclaimConflictEvent(this, event));
    }
}

// uk/codery/jclaim/spring/conflict/LoggingConflictSink.java
package uk.codery.jclaim.spring.conflict;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.codery.jclaim.event.ConflictEventSink;
import uk.codery.jclaim.event.EntityAttributesConflicted;

public final class LoggingConflictSink implements ConflictEventSink {
    private static final Logger log = LoggerFactory.getLogger(LoggingConflictSink.class);
    @Override public void accept(EntityAttributesConflicted event) {
        log.warn("JClaim conflict for {}: {} attribute(s) diverge",
                event.stored().id(), event.differences().size());
    }
}

// uk/codery/jclaim/spring/conflict/JclaimConflictEvent.java
package uk.codery.jclaim.spring.conflict;

import org.springframework.context.ApplicationEvent;
import uk.codery.jclaim.event.EntityAttributesConflicted;

/**
 * Spring {@link ApplicationEvent} carrying a JClaim
 * {@link EntityAttributesConflicted} payload. Published by the
 * starter's default {@code ConflictEventSink} so application code can
 * react via {@code @EventListener} methods.
 */
public final class JclaimConflictEvent extends ApplicationEvent {
    private final EntityAttributesConflicted payload;
    public JclaimConflictEvent(Object source, EntityAttributesConflicted payload) {
        super(source);
        this.payload = java.util.Objects.requireNonNull(payload, "payload");
    }
    public EntityAttributesConflicted payload() { return payload; }
}
```

**Step 4: Create the `AutoConfiguration.imports` registration**

`jclaim-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
uk.codery.jclaim.spring.JclaimAutoConfiguration
```

**Step 5: Run the tests, watch them pass**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimAutoConfigurationTest`
Expected: PASS — both tests green.

**Step 6: Commit**

```bash
git add jclaim-spring-boot-starter/
git commit -m "feat(spring-boot-starter): base auto-configuration with in-memory fallback

Wires DefaultEntityResolver against EntityStorage from the context.
Defaults to InMemoryEntityStorage when no adapter bean is present.
Registers SpringEventConflictSink as the default conflict bridge.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: Storage type property gating

**Files:**
- Modify: `jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/storage/InMemoryStorageConfiguration.java`
- Modify: `jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/JclaimAutoConfigurationTest.java`

**Step 1: Add the in-memory gating test**

Add to `JclaimAutoConfigurationTest`:
```java
@Test
void explicitInMemoryAlsoWorks() {
    runner.withPropertyValues("jclaim.storage.type=in-memory").run(ctx -> {
        assertThat(ctx).hasSingleBean(EntityStorage.class);
        assertThat(ctx.getBean(EntityStorage.class)).isInstanceOf(InMemoryEntityStorage.class);
    });
}
```

**Step 2: Run, expect pass (in-memory is the no-config default)**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimAutoConfigurationTest`
Expected: PASS.

**Step 3: Tighten `InMemoryStorageConfiguration` to honour `storage.type` when set explicitly**

The in-memory bean should register on `type=AUTO` (when no adapter wins) **and** explicit `type=IN_MEMORY`. Mongo/Postgres configurations will own the `type=MONGO`/`type=POSTGRES` cases. The `@ConditionalOnMissingBean(EntityStorage.class)` on the in-memory bean is sufficient to make this work — but we make the intent explicit by leaving the bean as-is and letting bean precedence + `@ConditionalOnMissingBean` carry the load.

No code change needed yet; the explicit test passes already because `@ConditionalOnMissingBean` covers it.

**Step 4: Commit**

```bash
git add jclaim-spring-boot-starter/src/test/
git commit -m "test(spring-boot-starter): cover explicit in-memory storage type

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: Mongo storage auto-configuration

**Files:**
- Modify: `jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/storage/MongoStorageConfiguration.java`
- Modify: `jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/JclaimAutoConfigurationTest.java`

**Step 1: Add Mongo wiring tests**

```java
@Test
void mongoStorageWiresWhenClientPresentAndTypeAuto() {
    runner
        .withUserConfiguration(MongoClientConfig.class)
        .run(ctx -> {
            assertThat(ctx.getBean(EntityStorage.class))
                    .isInstanceOf(uk.codery.jclaim.storage.mongo.MongoEntityStorage.class);
        });
}

@Test
void mongoTypeMissingClientFailsStartup() {
    runner
        .withPropertyValues("jclaim.storage.type=mongo")
        .run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("MongoClient");
        });
}

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
static class MongoClientConfig {
    @org.springframework.context.annotation.Bean
    com.mongodb.client.MongoClient mongoClient() {
        // org.mongodb:mongodb-driver-sync is on test classpath; we don't actually
        // connect — MongoEntityStorage construction calls createIndex against the
        // collection. Disable createIndexes to keep the unit test offline.
        return com.mongodb.client.MongoClients.create("mongodb://localhost:1");
    }
    @org.springframework.context.annotation.Bean
    JclaimPropertiesPostProcessor disableIndexes() {
        return p -> p.storage().mongo().setCreateIndexes(false);
    }
    @FunctionalInterface
    interface JclaimPropertiesPostProcessor extends org.springframework.beans.factory.config.BeanPostProcessor {
        @Override default Object postProcessBeforeInitialization(Object bean, String name) {
            if (bean instanceof JclaimProperties p) configure(p);
            return bean;
        }
        void configure(JclaimProperties properties);
    }
}
```

Note: this test exercises classpath + bean conditionals but stays offline. The "real" Mongo round-trip test lives in Task 10.

**Step 2: Run, watch them fail**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimAutoConfigurationTest`
Expected: FAILURE — Mongo bean isn't being created.

**Step 3: Implement `MongoStorageConfiguration`**

```java
package uk.codery.jclaim.spring.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.codery.jclaim.spring.JclaimProperties;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.mongo.MongoEntityStorage;

/**
 * Wires a {@link MongoEntityStorage} when the Mongo adapter is on the
 * classpath and a {@link MongoClient} bean is available, unless the user has
 * explicitly selected a different {@code jclaim.storage.type}.
 *
 * <p>The starter does not create the {@code MongoClient} itself — that's the
 * responsibility of {@code spring-boot-starter-data-mongodb} or equivalent.
 * If the user wants to point at a specific {@link MongoCollection}, they can
 * declare their own bean and the auto-configured one backs off.
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureBefore(InMemoryStorageConfiguration.class)
@ConditionalOnClass({MongoEntityStorage.class, MongoClient.class})
@ConditionalOnProperty(prefix = "jclaim.storage", name = "type",
        havingValue = "auto", matchIfMissing = true)
public class MongoStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(MongoClient.class)
    @ConditionalOnMissingBean(EntityStorage.class)
    static class MongoStorageWiring {

        @Bean
        @ConditionalOnMissingBean
        MongoCollection<Document> jclaimEntitiesCollection(
                MongoClient client, JclaimProperties properties) {
            return client.getDatabase(properties.storage().mongo().database())
                    .getCollection(properties.storage().mongo().collectionName(), Document.class);
        }

        @Bean
        EntityStorage jclaimMongoEntityStorage(
                MongoCollection<Document> collection, JclaimProperties properties) {
            return MongoEntityStorage.builder(collection)
                    .createIndexes(properties.storage().mongo().createIndexes())
                    .build();
        }
    }

    /**
     * When the user explicitly requested {@code jclaim.storage.type=mongo} but
     * no {@link MongoClient} bean is present, fail at startup with a clear
     * message rather than silently falling back to in-memory.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "jclaim.storage", name = "type", havingValue = "mongo")
    static class MongoRequiredFailFast {
        @Bean
        EntityStorage jclaimMongoStorageMissingClient(
                org.springframework.beans.factory.ObjectProvider<MongoClient> clients) {
            MongoClient client = clients.getIfAvailable();
            if (client == null) {
                throw new IllegalStateException(
                        "jclaim.storage.type=mongo requires a MongoClient bean. "
                                + "Add spring-boot-starter-data-mongodb (or define one).");
            }
            // Should never reach here when the auto-wiring above is active.
            throw new IllegalStateException("Unreachable");
        }
    }
}
```

**Step 4: Run, watch tests pass**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimAutoConfigurationTest`
Expected: PASS.

**Step 5: Commit**

```bash
git add jclaim-spring-boot-starter/
git commit -m "feat(spring-boot-starter): Mongo storage auto-configuration

Wires MongoEntityStorage when the adapter is on the classpath and a
MongoClient bean is present. Fails fast on jclaim.storage.type=mongo
without a MongoClient. Honours jclaim.storage.mongo.{database,collection-name,create-indexes}.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Postgres storage auto-configuration

**Files:**
- Modify: `jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/storage/PostgresStorageConfiguration.java`
- Modify: `jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/JclaimAutoConfigurationTest.java`

**Step 1: Add Postgres wiring tests**

```java
@Test
void postgresStorageWiresWhenDataSourcePresent() {
    runner
        .withUserConfiguration(DataSourceConfig.class)
        .withPropertyValues("jclaim.storage.postgres.apply-schema=false")
        .run(ctx -> {
            assertThat(ctx.getBean(EntityStorage.class))
                    .isInstanceOf(uk.codery.jclaim.storage.postgres.PostgresEntityStorage.class);
        });
}

@Test
void postgresTypeMissingDataSourceFailsStartup() {
    runner
        .withPropertyValues("jclaim.storage.type=postgres")
        .run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("DataSource");
        });
}

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
static class DataSourceConfig {
    @org.springframework.context.annotation.Bean
    javax.sql.DataSource dataSource() {
        // Lightweight non-connecting DataSource — adapter construction with
        // applySchema=false doesn't open a connection.
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://localhost:5432/jclaim_test");
        return ds;
    }
}
```

**Step 2: Run, watch them fail**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimAutoConfigurationTest`
Expected: FAILURE — Postgres adapter is not being wired.

**Step 3: Implement `PostgresStorageConfiguration`**

Mirror the Mongo structure:

```java
package uk.codery.jclaim.spring.storage;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.codery.jclaim.spring.JclaimProperties;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.postgres.PostgresEntityStorage;

@Configuration(proxyBeanMethods = false)
@AutoConfigureBefore({MongoStorageConfiguration.class, InMemoryStorageConfiguration.class})
@ConditionalOnClass({PostgresEntityStorage.class, DataSource.class})
@ConditionalOnProperty(prefix = "jclaim.storage", name = "type",
        havingValue = "auto", matchIfMissing = true)
public class PostgresStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(EntityStorage.class)
    static class PostgresStorageWiring {
        @Bean
        EntityStorage jclaimPostgresEntityStorage(
                DataSource dataSource, JclaimProperties properties) {
            return PostgresEntityStorage.builder(dataSource)
                    .applySchema(properties.storage().postgres().applySchema())
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "jclaim.storage", name = "type", havingValue = "postgres")
    static class PostgresRequiredFailFast {
        @Bean
        EntityStorage jclaimPostgresMissingDataSource(
                org.springframework.beans.factory.ObjectProvider<DataSource> dataSources) {
            DataSource ds = dataSources.getIfAvailable();
            if (ds == null) {
                throw new IllegalStateException(
                        "jclaim.storage.type=postgres requires a DataSource bean. "
                                + "Add spring-boot-starter-jdbc (or define one).");
            }
            throw new IllegalStateException("Unreachable");
        }
    }
}
```

Note: Postgres ordering — `@AutoConfigureBefore({MongoStorageConfiguration.class, InMemoryStorageConfiguration.class})` makes Postgres win over Mongo when both are on the classpath in `auto` mode. **Confirm with the user** at execution time whether this priority is what they want; if Mongo should win, swap the annotations.

**Step 4: Run, watch tests pass**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimAutoConfigurationTest`
Expected: PASS.

**Step 5: Commit**

```bash
git add jclaim-spring-boot-starter/
git commit -m "feat(spring-boot-starter): Postgres storage auto-configuration

Wires PostgresEntityStorage when the adapter is on the classpath and a
DataSource bean is present. Fails fast on jclaim.storage.type=postgres
without a DataSource. Honours jclaim.storage.postgres.apply-schema.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: SpringEventConflictSink wiring + event listener test

**Files:**
- Modify: `jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/conflict/SpringEventConflictSink.java` (already stubbed in Task 3 — verify it does the right thing)
- Create: `jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/SpringEventConflictSinkTest.java`

**Step 1: Write the failing event-listener integration test**

```java
package uk.codery.jclaim.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.conflict.JclaimConflictEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {JclaimAutoConfiguration.class,
        SpringEventConflictSinkTest.RecorderConfig.class})
class SpringEventConflictSinkTest {

    @Autowired EntityResolver resolver;
    @Autowired RecorderConfig.Recorder recorder;

    @Test
    void publishesJclaimConflictEventWhenAttributesDiverge() {
        Claim first = new Claim(SourceSystem.of("crm"), "user-1",
                List.of(MatchingAttribute.of("email", "alice@example.com")));
        resolver.resolveOrMint(first); // minted

        Claim conflicting = new Claim(SourceSystem.of("crm"), "user-1",
                List.of(MatchingAttribute.of("email", "alice+new@example.com")));
        resolver.resolveOrMint(conflicting); // matched + conflict

        assertThat(recorder.events).hasSize(1);
        JclaimConflictEvent event = recorder.events.get(0);
        assertThat(event.payload().differences()).hasSize(1);
        assertThat(event.payload().differences().get(0).name()).isEqualTo("email");
    }

    @TestConfiguration
    static class RecorderConfig {
        @org.springframework.context.annotation.Bean Recorder recorder() { return new Recorder(); }
        static class Recorder {
            final List<JclaimConflictEvent> events = new CopyOnWriteArrayList<>();
            @EventListener void on(JclaimConflictEvent event) { events.add(event); }
        }
    }
}
```

**Step 2: Run, watch it pass (the stub from Task 3 already does the right thing)**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=SpringEventConflictSinkTest`
Expected: PASS.

**Step 3: Add a test for `conflict-sink.type=log`**

```java
@Test
void loggingConflictSinkSwapsInOnProperty() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class))
        .withPropertyValues("jclaim.conflict-sink.type=log")
        .run(ctx -> assertThat(ctx.getBean(uk.codery.jclaim.event.ConflictEventSink.class))
                .isInstanceOf(uk.codery.jclaim.spring.conflict.LoggingConflictSink.class));
}

@Test
void noneConflictSinkSwapsInOnProperty() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class))
        .withPropertyValues("jclaim.conflict-sink.type=none")
        .run(ctx -> assertThat(ctx.getBean(uk.codery.jclaim.event.ConflictEventSink.class))
                .isNotInstanceOf(uk.codery.jclaim.spring.conflict.SpringEventConflictSink.class)
                .isNotInstanceOf(uk.codery.jclaim.spring.conflict.LoggingConflictSink.class));
}
```

**Step 4: Run, expect pass**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimAutoConfigurationTest`
Expected: PASS.

**Step 5: Commit**

```bash
git add jclaim-spring-boot-starter/src/
git commit -m "feat(spring-boot-starter): SpringEventConflictSink publishes JclaimConflictEvent

Default conflict bridge wraps EntityAttributesConflicted in a Spring
ApplicationEvent so @EventListener methods can observe conflicts.
Switchable via jclaim.conflict-sink.type to log/none.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: Health indicator

**Files:**
- Create: `jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/health/JclaimHealthIndicator.java`
- Create: `jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/health/JclaimHealthAutoConfiguration.java`
- Modify: `META-INF/spring/.../AutoConfiguration.imports` (add the health autoconfig)
- Create: `jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/health/JclaimHealthIndicatorTest.java`

**Step 1: Write a failing test for the indicator**

```java
package uk.codery.jclaim.spring.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class JclaimHealthIndicatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JclaimAutoConfiguration.class,
                    JclaimHealthAutoConfiguration.class,
                    HealthEndpointAutoConfiguration.class));

    @Test
    void registersIndicatorAndReportsUpForInMemoryStorage() {
        runner.run(ctx -> {
            HealthIndicator indicator = ctx.getBean("jclaimHealthIndicator", HealthIndicator.class);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        });
    }

    @Test
    void disabledByProperty() {
        runner.withPropertyValues("jclaim.health.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean("jclaimHealthIndicator");
        });
    }
}
```

**Step 2: Run, watch it fail**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimHealthIndicatorTest`
Expected: FAILURE — `JclaimHealthIndicator` does not exist.

**Step 3: Implement the indicator and its autoconfig**

`JclaimHealthIndicator.java`:
```java
package uk.codery.jclaim.spring.health;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import uk.codery.jclaim.id.UuidV7;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.storage.EntityStorage;

/**
 * Reports UP when the configured {@link EntityStorage} responds to a
 * cheap probe (a {@link EntityStorage#findByAlias} lookup on a
 * deliberately-impossible alias). Implementations can throw — the
 * indicator surfaces the exception message as the {@code "error"}
 * detail.
 */
public final class JclaimHealthIndicator extends AbstractHealthIndicator {

    private static final Alias PROBE = new Alias(
            SourceSystem.of("__jclaim_health_probe__"),
            "__health-check__");

    private final EntityStorage storage;
    public JclaimHealthIndicator(EntityStorage storage) { this.storage = storage; }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        storage.findByAlias(PROBE);
        builder.up().withDetail("storage", storage.getClass().getSimpleName());
    }
}
```

`JclaimHealthAutoConfiguration.java`:
```java
package uk.codery.jclaim.spring.health;

import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;
import uk.codery.jclaim.storage.EntityStorage;

@AutoConfiguration(after = JclaimAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "jclaim.health", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JclaimHealthAutoConfiguration {

    @Bean(name = "jclaimHealthIndicator")
    @ConditionalOnMissingBean(name = "jclaimHealthIndicator")
    public HealthIndicator jclaimHealthIndicator(EntityStorage storage) {
        return new JclaimHealthIndicator(storage);
    }
}
```

**Step 4: Register the new autoconfig**

Append to `AutoConfiguration.imports`:
```
uk.codery.jclaim.spring.health.JclaimHealthAutoConfiguration
```

**Step 5: Run, watch tests pass**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimHealthIndicatorTest`
Expected: PASS.

**Step 6: Commit**

```bash
git add jclaim-spring-boot-starter/
git commit -m "feat(spring-boot-starter): Actuator health indicator

Probes the configured EntityStorage via findByAlias on a sentinel
alias; surfaces the storage adapter class in details. Gated by
jclaim.health.enabled (default true) and Actuator on the classpath.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 9: Micrometer metrics

**Files:**
- Create: `jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/metrics/MeteredEntityResolver.java`
- Create: `jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/metrics/JclaimMetricsAutoConfiguration.java`
- Modify: `AutoConfiguration.imports` (add metrics autoconfig)
- Create: `jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/metrics/JclaimMetricsTest.java`

**Step 1: Write the failing metrics test**

```java
package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JclaimMetricsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JclaimAutoConfiguration.class, JclaimMetricsAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void countsMatchedAndMintedSeparately() {
        runner.run(ctx -> {
            EntityResolver resolver = ctx.getBean(EntityResolver.class);
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);

            Claim claim = new Claim(SourceSystem.of("crm"), "u-1",
                    List.of(MatchingAttribute.of("email", "a@x")));
            resolver.resolveOrMint(claim);
            resolver.resolveOrMint(claim);

            assertThat(registry.counter("jclaim.resolve", "outcome", "minted").count())
                    .isEqualTo(1.0);
            assertThat(registry.counter("jclaim.resolve", "outcome", "matched").count())
                    .isEqualTo(1.0);
        });
    }

    @Test
    void disabledByProperty() {
        runner.withPropertyValues("jclaim.metrics.enabled=false").run(ctx -> {
            // Resolver bean is no longer the metered wrapper
            assertThat(ctx.getBean(EntityResolver.class))
                    .isNotInstanceOf(MeteredEntityResolver.class);
        });
    }
}
```

**Step 2: Run, watch it fail**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimMetricsTest`
Expected: FAILURE — classes don't exist.

**Step 3: Implement the metered resolver and autoconfig**

`MeteredEntityResolver.java`:
```java
package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;

import java.util.Optional;
import java.util.Set;

/** Decorator that records resolver-method counters and timers. */
public final class MeteredEntityResolver implements EntityResolver {

    private final EntityResolver delegate;
    private final MeterRegistry registry;
    private final Timer resolveTimer;

    public MeteredEntityResolver(EntityResolver delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
        this.resolveTimer = Timer.builder("jclaim.resolve.duration").register(registry);
    }

    @Override
    public ResolutionResult resolveOrMint(Claim claim) {
        return resolveTimer.record(() -> {
            ResolutionResult result = delegate.resolveOrMint(claim);
            String outcome = switch (result) {
                case ResolutionResult.Matched m -> "matched";
                case ResolutionResult.Minted m -> "minted";
            };
            registry.counter("jclaim.resolve", "outcome", outcome).increment();
            return result;
        });
    }

    @Override public Entity getByUrn(EntityId urn) { return delegate.getByUrn(urn); }
    @Override public Optional<Entity> findByHumanId(String humanId) { return delegate.findByHumanId(humanId); }
    @Override public Optional<Entity> findByAlias(SourceSystem source, String sourceId) { return delegate.findByAlias(source, sourceId); }
    @Override public Entity addAlias(EntityId urn, SourceSystem source, String sourceId) { return delegate.addAlias(urn, source, sourceId); }

    @Override
    public Set<Entity> findCandidates(Claim claim) {
        registry.counter("jclaim.findCandidates").increment();
        return delegate.findCandidates(claim);
    }
}
```

`JclaimMetricsAutoConfiguration.java`:
```java
package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.spring.JclaimAutoConfiguration;

@AutoConfiguration(after = JclaimAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "jclaim.metrics", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JclaimMetricsAutoConfiguration {

    @Bean
    @Primary
    public EntityResolver meteredEntityResolver(
            @org.springframework.beans.factory.annotation.Qualifier("jclaimEntityResolver")
            EntityResolver delegate,
            MeterRegistry registry) {
        return new MeteredEntityResolver(delegate, registry);
    }
}
```

Note: by registering a `@Primary` decorator we leave the original `jclaimEntityResolver` bean in the context (so users that wire by name still work), but `@Autowired EntityResolver` resolves to the metered wrapper.

**Step 4: Run, watch tests pass**

Run: `mvn -q -pl jclaim-spring-boot-starter test -Dtest=JclaimMetricsTest`
Expected: PASS.

**Step 5: Register the metrics autoconfig**

Append to `AutoConfiguration.imports`:
```
uk.codery.jclaim.spring.metrics.JclaimMetricsAutoConfiguration
```

**Step 6: Commit**

```bash
git add jclaim-spring-boot-starter/
git commit -m "feat(spring-boot-starter): Micrometer metrics decorator

Wraps the EntityResolver bean with a MeteredEntityResolver that
publishes jclaim.resolve counters (tagged by outcome) and a duration
timer. Gated by jclaim.metrics.enabled and a MeterRegistry bean on the
classpath.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 10: Testcontainers-backed end-to-end integration tests

**Files:**
- Create: `jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/MongoIntegrationTest.java`
- Create: `jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/PostgresIntegrationTest.java`

**Step 1: Add a Testcontainers-backed `@SpringBootTest` for Mongo**

```java
package uk.codery.jclaim.spring;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.mongo.MongoEntityStorage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {JclaimAutoConfiguration.class, MongoIntegrationTest.MongoConfig.class})
@Testcontainers
class MongoIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("jclaim.storage.type", () -> "mongo");
    }

    @TestConfiguration
    static class MongoConfig {
        @Bean MongoClient mongoClient() { return MongoClients.create(MONGO.getReplicaSetUrl()); }
    }

    @Autowired EntityResolver resolver;
    @Autowired EntityStorage storage;

    @Test
    void mintsAndMatches() {
        assertThat(storage).isInstanceOf(MongoEntityStorage.class);
        Claim c = new Claim(SourceSystem.of("crm"), "u-1",
                List.of(MatchingAttribute.of("email", "a@x")));
        ResolutionResult first = resolver.resolveOrMint(c);
        ResolutionResult second = resolver.resolveOrMint(c);
        assertThat(first).isInstanceOf(ResolutionResult.Minted.class);
        assertThat(second).isInstanceOf(ResolutionResult.Matched.class);
    }
}
```

**Step 2: Mirror for Postgres**

```java
@Testcontainers
@SpringBootTest(classes = {JclaimAutoConfiguration.class, PostgresIntegrationTest.PgConfig.class})
class PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("jclaim.storage.type", () -> "postgres");
    }

    @TestConfiguration
    static class PgConfig {
        @Bean DataSource dataSource() {
            var ds = new org.postgresql.ds.PGSimpleDataSource();
            ds.setUrl(PG.getJdbcUrl());
            ds.setUser(PG.getUsername());
            ds.setPassword(PG.getPassword());
            return ds;
        }
    }
    // identical body to MongoIntegrationTest
}
```

**Step 3: Run, watch them pass (requires Docker)**

Run: `mvn -q -pl jclaim-spring-boot-starter verify`
Expected: PASS when Docker is available. The earlier `ApplicationContextRunner` tests still cover the no-Docker case.

**Step 4: Commit**

```bash
git add jclaim-spring-boot-starter/src/test/
git commit -m "test(spring-boot-starter): Testcontainers integration tests for Mongo + Postgres

Round-trip a Claim through resolveOrMint to prove the auto-configured
resolver actually persists against the real adapter for each backend.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 11: Documentation + verification

**Files:**
- Create: `jclaim-spring-boot-starter/README.md`
- Modify: `README.md` (main, add the starter to Installation + Modules)
- Modify: `CHANGELOG.md` (Unreleased: new starter module)
- Modify: `CLAUDE.md` (Project Status: starter delivered)

**Step 1: Write `jclaim-spring-boot-starter/README.md`**

Sections:
- What the starter does
- Maven dependency block
- Quick Start (zero-config in-memory + add Mongo/Postgres adapter)
- Properties table (all keys, defaults, explanations)
- Conflict event handling with `@EventListener`
- Override patterns (custom `EntityStorage`, `ConflictEventSink`, `EntityResolver`)
- Observability (Actuator health, Micrometer metrics)

**Step 2: Update main `README.md`**

- Add starter to the dependency block in Installation
- Add a "Spring Boot users" pointer under Quick Start linking to the starter README
- Update Modules section

**Step 3: Update `CHANGELOG.md`**

Add under `[Unreleased]`:
```
### Added
- `jclaim-spring-boot-starter` — Spring Boot 3.x auto-configuration with
  classpath-driven storage selection, Spring event bridge for conflicts,
  Actuator health indicator and Micrometer metrics.
```

**Step 4: Update `CLAUDE.md` Project Status**

- Change "Next session" line to point to the matching policy DSL as the next thing
- Add `jclaim-spring-boot-starter` to the Layout section

**Step 5: Full reactor verification**

Run:
```bash
mvn clean verify
```
Expected: BUILD SUCCESS. Four modules built, all tests pass (Docker-gated integration tests run if Docker is available).

**Step 6: Re-verify the Spring-independence invariant**

Run: `grep -RIn "springframework\|spring-boot" jclaim-core/pom.xml jclaim-storage-mongo/pom.xml jclaim-storage-postgres/pom.xml`
Expected: no matches. Core and adapters stay clean.

**Step 7: Commit docs**

```bash
git add jclaim-spring-boot-starter/README.md README.md CHANGELOG.md CLAUDE.md
git commit -m "docs(spring-boot-starter): module README + main README + CHANGELOG

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Out-of-scope reminders (do not implement)

- JSPEC integration / matching policy DSL — next effort, separate session
- Maven Central publication — happens at the suite level after the DSL session
- `@EnableJclaim` annotation — anti-pattern in Spring Boot 3.x; auto-configuration replaces it
- Reactive support — synchronous only for v0.x
- Auto-creating `MongoClient` or `DataSource` beans — that's Spring Data's job
- Adding Spring to `jclaim-core` — never

## Verification checklist

- [ ] `mvn clean verify` green across the reactor
- [ ] Four modules all build + test
- [ ] `jclaim-core/pom.xml` has zero Spring dependencies (grep before claiming done)
- [ ] `ApplicationContextRunner` tests cover every auto-config scenario from the brief
- [ ] Testcontainers tests confirm the wiring round-trips against real Mongo + Postgres
- [ ] FOSSA scan passes (Apache 2.0 on Spring Boot + Micrometer)
- [ ] A trivial Spring Boot app with **only** `jclaim-spring-boot-starter` on the classpath gets a working in-memory `EntityResolver` bean (verify manually after Task 11)
