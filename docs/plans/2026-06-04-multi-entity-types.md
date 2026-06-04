# Multiple Entity Types Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let one application reconcile several entity types (customer, vehicle, order, …) — each its own resolver over its own physically-isolated storage scope — configured via a `jclaim.entity-types.<type>` map and selected by qualified bean or an `EntityResolvers` facade.

**Architecture:** Core gains a small Spring-free `EntityResolvers` registry and a wrong-type/namespace guard at the resolver's URN boundary; the storage **port stays frozen** — isolation is achieved by instantiating N separately-scoped adapter instances (Postgres schema-per-type, Mongo collection-per-type, in-memory instance-per-type). The Spring starter binds the `entity-types` map and dynamically registers one resolver bean per type (prefixed bean name + type-key qualifier) plus the facade, with per-type metrics/health.

**Tech Stack:** Java 21, Maven (multi-module reactor), Spring Boot 3.x auto-configuration, plain JDBC (Postgres), MongoDB sync driver, JUnit 5, Testcontainers, AssertJ, Micrometer.

**Design reference:** `docs/plans/2026-06-04-multi-entity-types-design.md` (read it first — decisions table + Addendum A–H).

---

## Pre-flight facts (verified against the worktree — do not re-derive)

- `EntityResolver` already declares `getByUrn(EntityId)`, `findByHumanId(String)`, `findByAlias(SourceSystem,String)`, `addAlias(EntityId,SourceSystem,String)`, `addAlias(EntityId,Alias)` default, `findCandidates(Claim)`, `resolveOrMint(Claim)`. Addendum A guards **existing** methods.
- `EntityId` (a `record EntityId(String urn)`) validates segments via `URN_PATTERN` (`SEGMENT = [A-Za-z0-9][A-Za-z0-9-]*`) in its compact constructor. `EntityId.of(namespace,type,uuid)` builds the URN and therefore validates both segments + non-blank. Accessors: `namespace()`, `type()`, `uuid()`. Constants `DEFAULT_NAMESPACE="codery"`, `DEFAULT_TYPE="entity"`.
- `DefaultEntityResolver` stores `namespace`, `entityType` as fields; `Builder` has setters `namespace`, `entityType`, `humanIdTemplate`, `humanIdGenerator`, `matchingPolicy`, `maxCandidates`, `matchEventSink`, `uuidSupplier`, `clock`; `build()` currently does **no** namespace/type validation.
- `PostgresEntityStorage`: private ctor `(DataSource, ObjectMapper)`; static factory `PostgresEntityStorage.create(DataSource)` and `.builder(DataSource)`; `applySchema()` runs `schema.sql`; **all SQL uses unqualified table names** (`entities`, `entity_aliases`, `entity_attributes`). Builder fields `applySchema=true`, `objectMapper`.
- `MongoEntityStorage`: private ctor `(MongoCollection<Document>)`; static `create(collection)` + `.builder(collection)`; `createIndexes()` builds the unique alias + partial-unique humanId indexes. Collection name is a construction input.
- Starter single-resolver wiring lives in `JclaimAutoConfiguration` (`@Bean("jclaimResolver") @ConditionalOnMissingBean`). Metrics (`JclaimMetricsAutoConfiguration`) wraps it with a `@Primary MeteredEntityResolver` and is gated `@ConditionalOnBean(name="jclaimResolver")`. Health (`JclaimHealthAutoConfiguration`) wraps the single `EntityStorage` bean.
- Postgres test support hands each storage its own `PGSimpleDataSource` with `setCurrentSchema(schema)` after `CREATE SCHEMA` — i.e. the per-type-own-connection escape-hatch shape already works end-to-end through the contract suite.

## Conventions for every task

- **TDD always:** failing test → run-it-fail → minimal impl → run-it-pass → commit. Never write impl before a red test.
- **Run a single test:** `mvn -pl <module> test -Dtest=ClassName#methodName` (e.g. `-pl jclaim-core`). Run a class: `-Dtest=ClassName`. Container tests need Docker; they self-skip via `RequiresDockerCondition` when Docker is absent — if you have Docker, they run.
- **Coverage gate:** the parent POM enforces JaCoCo **lines AND branches ≥ 80%** per module (the gate text only mentions lines, but verify branches too — see project memory). Keep new branches tested.
- **Spring-independence:** nothing under `uk.codery.jclaim.*` in `jclaim-core` / adapters may import `org.springframework.*`.
- **Commit cadence:** one commit per task (after green). Use the message shown in each task's final step.
- After each phase, run the **whole reactor**: `mvn -T1C test` → expect `BUILD SUCCESS`.

---

# Phase 1 — Core: `EntityResolvers` registry, eager validation, wrong-type guard

Lowest-risk, Spring-free. No storage changes.

### Task 1.1: Eager namespace/type validation helper on `EntityId`

**Files:**
- Modify: `jclaim-core/src/main/java/uk/codery/jclaim/model/EntityId.java`
- Test: `jclaim-core/src/test/java/uk/codery/jclaim/model/EntityIdTest.java`

**Step 1 — failing test.** Add to `EntityIdTest`:

```java
@Test
void requireValidSegment_acceptsWellFormedSegment() {
    // Does not throw.
    EntityId.requireValidSegment("type", "customer");
    EntityId.requireValidSegment("namespace", "acme-corp");
}

@Test
void requireValidSegment_rejectsBlank() {
    assertThatThrownBy(() -> EntityId.requireValidSegment("type", "  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("type");
}

@Test
void requireValidSegment_rejectsIllegalCharacters() {
    assertThatThrownBy(() -> EntityId.requireValidSegment("type", "cust omer"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cust omer");
    assertThatThrownBy(() -> EntityId.requireValidSegment("namespace", "-leading"))
            .isInstanceOf(IllegalArgumentException.class);
}
```

**Step 2 — run, expect fail:** `mvn -pl jclaim-core test -Dtest=EntityIdTest` → FAIL (`requireValidSegment` not defined).

**Step 3 — implement.** In `EntityId.java`, expose a public static validator reusing the existing `SEGMENT` pattern. Add a compiled `SEGMENT_PATTERN` and the method:

```java
private static final Pattern SEGMENT_PATTERN = Pattern.compile("^" + SEGMENT + "$");

/**
 * Validates a single URN path segment (namespace or type). Throws
 * {@link IllegalArgumentException} naming {@code label} and the offending
 * {@code value} if it is blank or contains characters outside
 * {@code [A-Za-z0-9][A-Za-z0-9-]*}. Used for fail-fast configuration validation.
 */
public static void requireValidSegment(String label, String value) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) {
        throw new IllegalArgumentException(label + " must not be blank");
    }
    if (!SEGMENT_PATTERN.matcher(value).matches()) {
        throw new IllegalArgumentException(
                "Invalid " + label + " segment: '" + value
                        + "' (must match " + SEGMENT + ")");
    }
}
```

**Step 4 — run, expect pass:** `mvn -pl jclaim-core test -Dtest=EntityIdTest` → PASS.

**Step 5 — commit:**
```bash
git add jclaim-core/src/main/java/uk/codery/jclaim/model/EntityId.java \
        jclaim-core/src/test/java/uk/codery/jclaim/model/EntityIdTest.java
git commit -m "feat(core): add EntityId.requireValidSegment for fail-fast URN validation"
```

### Task 1.2: `build()` validates namespace + type eagerly

**Files:**
- Modify: `jclaim-core/src/main/java/uk/codery/jclaim/resolver/DefaultEntityResolver.java` (Builder.build, ~line 390)
- Test: `jclaim-core/src/test/java/uk/codery/jclaim/resolver/DefaultEntityResolverTest.java`

**Step 1 — failing test:**

```java
@Test
void build_rejectsBlankEntityType() {
    assertThatThrownBy(() -> DefaultEntityResolver.builder(new InMemoryEntityStorage())
            .entityType("  ")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("type");
}

@Test
void build_rejectsIllegalNamespace() {
    assertThatThrownBy(() -> DefaultEntityResolver.builder(new InMemoryEntityStorage())
            .namespace("bad namespace")
            .build())
            .isInstanceOf(IllegalArgumentException.class);
}
```

**Step 2 — run, expect fail:** `mvn -pl jclaim-core test -Dtest=DefaultEntityResolverTest#build_rejectsBlankEntityType` → FAIL (currently builds fine, throws later at mint).

**Step 3 — implement.** Change `build()`:

```java
public DefaultEntityResolver build() {
    EntityId.requireValidSegment("namespace", namespace);
    EntityId.requireValidSegment("type", entityType);
    return new DefaultEntityResolver(this);
}
```

**Step 4 — run, expect pass:** the two new tests + full `-Dtest=DefaultEntityResolverTest` → PASS (existing tests use valid values).

**Step 5 — commit:**
```bash
git add jclaim-core/src/main/java/uk/codery/jclaim/resolver/DefaultEntityResolver.java \
        jclaim-core/src/test/java/uk/codery/jclaim/resolver/DefaultEntityResolverTest.java
git commit -m "feat(core): eagerly validate namespace/type at resolver build()"
```

### Task 1.3: Wrong-type / foreign-namespace URN guard (Addendum A)

Guard every URN-accepting entry point: `getByUrn` and `addAlias(EntityId, …)`. A URN whose `namespace()` or `type()` differs from this resolver's configured values is the **wrong resolver**, not a missing entity.

**Files:**
- Modify: `jclaim-core/.../resolver/DefaultEntityResolver.java` (getByUrn ~199, addAlias ~217)
- Test: `DefaultEntityResolverTest.java`

**Step 1 — failing test:**

```java
@Test
void getByUrn_rejectsForeignType() {
    var resolver = DefaultEntityResolver.builder(new InMemoryEntityStorage())
            .namespace("acme").entityType("customer").build();
    var vehicleUrn = EntityId.of("acme", "vehicle", java.util.UUID.randomUUID());
    assertThatThrownBy(() -> resolver.getByUrn(vehicleUrn))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("customer")   // resolver's type
            .hasMessageContaining("vehicle");   // received type
}

@Test
void getByUrn_rejectsForeignNamespace() {
    var resolver = DefaultEntityResolver.builder(new InMemoryEntityStorage())
            .namespace("acme").entityType("customer").build();
    var foreign = EntityId.of("other", "customer", java.util.UUID.randomUUID());
    assertThatThrownBy(() -> resolver.getByUrn(foreign))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void addAlias_rejectsForeignTypeUrn() {
    var resolver = DefaultEntityResolver.builder(new InMemoryEntityStorage())
            .namespace("acme").entityType("customer").build();
    var vehicleUrn = EntityId.of("acme", "vehicle", java.util.UUID.randomUUID());
    assertThatThrownBy(() -> resolver.addAlias(vehicleUrn, SourceSystem.of("crm"), "x"))
            .isInstanceOf(IllegalArgumentException.class);
}
```

**Step 2 — run, expect fail:** `-Dtest=DefaultEntityResolverTest#getByUrn_rejectsForeignType` → FAIL (returns not-found / proceeds).

**Step 3 — implement.** Add a private guard and call it first in both methods:

```java
private void requireOwnUrn(EntityId urn) {
    Objects.requireNonNull(urn, "urn");
    if (!namespace.equals(urn.namespace()) || !entityType.equals(urn.type())) {
        throw new IllegalArgumentException(
                "URN " + urn + " belongs to (" + urn.namespace() + ", " + urn.type()
                        + ") but this resolver reconciles (" + namespace + ", " + entityType + ")");
    }
}
```

Call `requireOwnUrn(urn);` as the first statement of `getByUrn(EntityId)` and `addAlias(EntityId, SourceSystem, String)`.

**Step 4 — run, expect pass:** new tests + full `-Dtest=DefaultEntityResolverTest` → PASS.

**Step 5 — commit:**
```bash
git commit -am "feat(core): reject foreign-type/namespace URNs at resolver boundary"
```

### Task 1.4: `EntityResolvers` registry — construction + `find`/`types`

**Files:**
- Create: `jclaim-core/src/main/java/uk/codery/jclaim/resolver/EntityResolvers.java`
- Test: `jclaim-core/src/test/java/uk/codery/jclaim/resolver/EntityResolversTest.java`

**Step 1 — failing test:**

```java
package uk.codery.jclaim.resolver;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class EntityResolversTest {

    private static EntityResolver resolver(String type) {
        return DefaultEntityResolver.builder(new InMemoryEntityStorage())
                .namespace("acme").entityType(type).build();
    }

    @Test
    void types_returnsAllKeys() {
        var map = new LinkedHashMap<String, EntityResolver>();
        map.put("customer", resolver("customer"));
        map.put("vehicle", resolver("vehicle"));
        var resolvers = EntityResolvers.of(map);
        assertThat(resolvers.types()).containsExactlyInAnyOrder("customer", "vehicle");
    }

    @Test
    void find_returnsPresentAndEmpty() {
        var resolvers = EntityResolvers.of(Map.of("customer", resolver("customer")));
        assertThat(resolvers.find("customer")).isPresent();
        assertThat(resolvers.find("vehicle")).isEmpty();
    }

    @Test
    void of_rejectsBlankKey() {
        assertThatThrownBy(() -> EntityResolvers.of(Map.of("  ", resolver("x"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsNullResolver() {
        var map = new java.util.HashMap<String, EntityResolver>();
        map.put("customer", null);
        assertThatThrownBy(() -> EntityResolvers.of(map))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void of_isDefensivelyCopied() {
        var map = new LinkedHashMap<String, EntityResolver>();
        map.put("customer", resolver("customer"));
        var resolvers = EntityResolvers.of(map);
        map.put("vehicle", resolver("vehicle"));            // mutate source after construction
        assertThat(resolvers.types()).containsExactly("customer");
    }
}
```

**Step 2 — run, expect fail:** `mvn -pl jclaim-core test -Dtest=EntityResolversTest` → FAIL (class missing).

**Step 3 — implement.** Create `EntityResolvers.java`:

```java
package uk.codery.jclaim.resolver;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, Spring-free registry of {@link EntityResolver}s keyed by URN type
 * segment. The selection facade for multi-entity-type applications: look a
 * resolver up by type, or iterate every configured type.
 *
 * <p>Usable without Spring — embedders that build N resolvers can wrap them
 * here. The starter exposes an instance as a bean.
 */
public final class EntityResolvers {

    private final Map<String, EntityResolver> byType;

    private EntityResolvers(Map<String, EntityResolver> byType) {
        this.byType = byType;
    }

    /** Builds a registry from a type→resolver map (defensively copied). */
    public static EntityResolvers of(Map<String, EntityResolver> byType) {
        Objects.requireNonNull(byType, "byType");
        var copy = new java.util.LinkedHashMap<String, EntityResolver>();
        byType.forEach((type, resolver) -> {
            Objects.requireNonNull(type, "type");
            if (type.isBlank()) {
                throw new IllegalArgumentException("entity type key must not be blank");
            }
            copy.put(type, Objects.requireNonNull(resolver, "resolver for type " + type));
        });
        return new EntityResolvers(Map.copyOf(copy));
    }

    /** The resolver for {@code type}, or empty if no such type is configured. */
    public Optional<EntityResolver> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    /** The set of configured entity-type segments. */
    public Set<String> types() {
        return byType.keySet();
    }

    @Override
    public String toString() {
        return "EntityResolvers" + byType.keySet();
    }
}
```

**Step 4 — run, expect pass:** `-Dtest=EntityResolversTest` → PASS.

**Step 5 — commit:**
```bash
git add jclaim-core/src/main/java/uk/codery/jclaim/resolver/EntityResolvers.java \
        jclaim-core/src/test/java/uk/codery/jclaim/resolver/EntityResolversTest.java
git commit -m "feat(core): add EntityResolvers registry (find/types)"
```

### Task 1.5: `EntityResolvers.forType` throws listing known types

**Files:** Modify `EntityResolvers.java`; extend `EntityResolversTest.java`.

**Step 1 — failing test:**

```java
@Test
void forType_returnsResolver() {
    var r = resolver("customer");
    var resolvers = EntityResolvers.of(Map.of("customer", r));
    assertThat(resolvers.forType("customer")).isSameAs(r);
}

@Test
void forType_unknownThrowsListingKnownTypes() {
    var resolvers = EntityResolvers.of(Map.of(
            "customer", resolver("customer"), "vehicle", resolver("vehicle")));
    assertThatThrownBy(() -> resolvers.forType("order"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("order")
            .hasMessageContaining("customer")
            .hasMessageContaining("vehicle");
}
```

**Step 2 — run, expect fail:** `-Dtest=EntityResolversTest#forType_unknownThrowsListingKnownTypes` → FAIL.

**Step 3 — implement:**

```java
/** The resolver for {@code type}; throws naming the known types if absent. */
public EntityResolver forType(String type) {
    EntityResolver resolver = byType.get(type);
    if (resolver == null) {
        throw new IllegalArgumentException(
                "No resolver for entity type '" + type + "'. Known types: " + byType.keySet());
    }
    return resolver;
}
```

**Step 4 — run, expect pass.** **Step 5 — commit:**
```bash
git commit -am "feat(core): EntityResolvers.forType throws listing known types"
```

### Task 1.6: Phase-1 reactor gate

Run `mvn -pl jclaim-core test` → `BUILD SUCCESS`. Then `mvn -T1C test` (whole reactor) → `BUILD SUCCESS`. No commit (verification only). If JaCoCo fails on branch coverage for the new guard, add the missing-branch test before proceeding.

---

# Phase 2 — In-memory & Mongo isolation tests (no adapter change)

Prove physical isolation for the two adapters that need **zero production change**, so later phases build on a verified baseline. These tests live beside each adapter.

### Task 2.1: In-memory instance-per-type isolation

**Files:**
- Test: `jclaim-core/src/test/java/uk/codery/jclaim/storage/memory/InMemoryIsolationTest.java`

**Step 1 — failing test** (it will actually pass once written, because two instances are inherently isolated — but write it to lock the guarantee and to mirror the Mongo/Postgres tests):

```java
package uk.codery.jclaim.storage.memory;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.*;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.resolver.EntityResolver;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIsolationTest {

    private static EntityResolver resolver(String type) {
        return DefaultEntityResolver.builder(new InMemoryEntityStorage())
                .namespace("acme").entityType(type)
                .humanIdTemplate("????-????-?")
                .build();
    }

    @Test
    void sameAliasResolvesIndependentlyPerType() {
        var customers = resolver("customer");
        var vehicles = resolver("vehicle");
        var source = SourceSystem.of("crm");

        var c = customers.resolveOrMint(Claim.of(source, "shared-id"));
        var v = vehicles.resolveOrMint(Claim.of(source, "shared-id"));

        // Same (source, sourceId) maps to DIFFERENT canonical entities per type.
        assertThat(c.entity().id()).isNotEqualTo(v.entity().id());
        assertThat(c.entity().id().type()).isEqualTo("customer");
        assertThat(v.entity().id().type()).isEqualTo("vehicle");
    }

    @Test
    void humanIdMintedUnderOneTypeInvisibleToOther() {
        var customers = resolver("customer");
        var vehicles = resolver("vehicle");
        var minted = customers.resolveOrMint(Claim.of(SourceSystem.of("crm"), "c1")).entity();
        assertThat(minted.humanId()).isNotNull();
        assertThat(vehicles.findByHumanId(minted.humanId())).isEmpty();
    }
}
```

> Confirm `Claim.of(SourceSystem, String)` and `ResolutionResult.entity()` exist; if the factory differs, adapt to the real `Claim` constructor (check `model/Claim.java`). `ResolutionResult` is sealed `Matched|Minted`; both expose `entity()`.

**Step 2 — run:** `mvn -pl jclaim-core test -Dtest=InMemoryIsolationTest` → PASS (documents the guarantee).

**Step 5 — commit:**
```bash
git add jclaim-core/src/test/java/uk/codery/jclaim/storage/memory/InMemoryIsolationTest.java
git commit -m "test(core): pin in-memory instance-per-type isolation"
```

### Task 2.2: Mongo collection-per-type isolation

**Files:**
- Test: `jclaim-storage-mongo/src/test/java/uk/codery/jclaim/storage/mongo/MongoIsolationTest.java`

Use `MongoTestSupport.newCollection()` to obtain two distinct collections on the **same** `MongoClient`/database, build a resolver per collection, and assert the same alias + humanId are independent. Gate with `@ExtendWith(RequiresDockerCondition.class)`.

**Step 1 — failing test:**

```java
package uk.codery.jclaim.storage.mongo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.codery.jclaim.model.*;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.storage.mongo.support.MongoTestSupport;
import uk.codery.jclaim.testsupport.RequiresDockerCondition;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(RequiresDockerCondition.class)
class MongoIsolationTest {

    private static EntityResolver resolver(String type) {
        var storage = MongoEntityStorage.create(MongoTestSupport.newCollection());
        return DefaultEntityResolver.builder(storage)
                .namespace("acme").entityType(type).humanIdTemplate("????-????-?").build();
    }

    @Test
    void sameAliasIndependentAcrossCollections() {
        var customers = resolver("customer");
        var vehicles = resolver("vehicle");
        var src = SourceSystem.of("crm");
        var c = customers.resolveOrMint(Claim.of(src, "shared")).entity();
        var v = vehicles.resolveOrMint(Claim.of(src, "shared")).entity();
        assertThat(c.id()).isNotEqualTo(v.id());
        assertThat(vehicles.findByAlias(src, "shared").map(Entity::id)).contains(v.id());
        assertThat(customers.findByAlias(src, "shared").map(Entity::id)).contains(c.id());
    }

    @Test
    void humanIdScopedPerCollection() {
        var customers = resolver("customer");
        var vehicles = resolver("vehicle");
        var minted = customers.resolveOrMint(Claim.of(SourceSystem.of("crm"), "c1")).entity();
        assertThat(vehicles.findByHumanId(minted.humanId())).isEmpty();
    }
}
```

> Verify the `RequiresDockerCondition` import path from `MongoEntityStorageContractTest` (it extends the same condition). Match whatever package it actually lives in.

**Step 2 — run:** `mvn -pl jclaim-storage-mongo test -Dtest=MongoIsolationTest` → PASS (Docker present) or skipped.

**Step 5 — commit:**
```bash
git add jclaim-storage-mongo/src/test/java/uk/codery/jclaim/storage/mongo/MongoIsolationTest.java
git commit -m "test(mongo): pin collection-per-type isolation on a shared client"
```

### Task 2.3: Phase-2 reactor gate

`mvn -T1C test` → `BUILD SUCCESS`.

---

# Phase 3 — Postgres schema-per-type (the one real adapter change)

Add a `.schema(String)` builder param. `null`/blank ⇒ today's behaviour exactly (no qualification). When set: `CREATE SCHEMA IF NOT EXISTS` during provisioning, and **schema-qualified, validated, quoted** table references in every query (Addendum C option 1 — avoids `search_path`/rollback fragility entirely).

Implementation shape: hold a computed prefix `tablePrefix` = `""` when no schema, else `"\"<schema>\"."`. Build all SQL strings from a single set of `T_ENTITIES`, `T_ALIASES`, `T_ATTRS` instance fields so qualification is centralised, not sprinkled. The schema name is validated with `EntityId.requireValidSegment` (closes the injection vector) before quoting.

### Task 3.1: Schema validation + builder param (no query change yet)

**Files:**
- Modify: `jclaim-storage-postgres/.../PostgresEntityStorage.java`
- Test: `jclaim-storage-postgres/src/test/java/uk/codery/jclaim/storage/postgres/PostgresSchemaScopeTest.java` (new, no Docker needed for the validation test)

**Step 1 — failing test:**

```java
package uk.codery.jclaim.storage.postgres;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import static org.assertj.core.api.Assertions.*;

class PostgresSchemaScopeTest {

    @Test
    void builder_rejectsIllegalSchemaIdentifier() {
        var ds = new PGSimpleDataSource();   // not connected; validation happens before any SQL
        ds.setUrl("jdbc:postgresql://localhost:1/none");
        assertThatThrownBy(() -> PostgresEntityStorage.builder(ds)
                .applySchema(false)
                .schema("bad schema; DROP TABLE")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void builder_acceptsNullSchemaAsDefault() {
        var ds = new PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://localhost:1/none");
        // applySchema(false) + null schema must not throw and must not touch the DB.
        assertThatCode(() -> PostgresEntityStorage.builder(ds).applySchema(false).schema(null).build())
                .doesNotThrowAnyException();
    }
}
```

**Step 2 — run, expect fail:** `mvn -pl jclaim-storage-postgres test -Dtest=PostgresSchemaScopeTest` → FAIL (`schema(...)` missing).

**Step 3 — implement.** In `PostgresEntityStorage`:

- Add field `private final String tablePrefix;` and constants computed from schema.
- Change ctor to accept the schema (validated, quoted) and compute the prefix:

```java
private final DataSource dataSource;
private final ObjectMapper objectMapper;
private final String schema;          // null when unscoped
private final String tablePrefix;     // "" or "\"<schema>\"."

private PostgresEntityStorage(DataSource dataSource, ObjectMapper objectMapper, String schema) {
    this.dataSource = dataSource;
    this.objectMapper = objectMapper;
    this.schema = (schema == null || schema.isBlank()) ? null : schema;
    if (this.schema != null) {
        EntityId.requireValidSegment("schema", this.schema);   // identifier-safe + injection-safe
    }
    this.tablePrefix = this.schema == null ? "" : "\"" + this.schema + "\".";
}
```

- Builder: add `private String schema;` + setter `schema(String)` returning `this`; pass it to the ctor in `build()`. Keep `applySchema` behaviour.
- Adjust the `create(DataSource)` static factory to pass `schema=null`.

> Don't touch the query strings in this task — only validation + plumbing. Existing contract tests still pass because `tablePrefix` is unused so far.

**Step 4 — run, expect pass:** `-Dtest=PostgresSchemaScopeTest` and `-Dtest=PostgresEntityStorageContractTest` (Docker) → PASS.

**Step 5 — commit:**
```bash
git commit -am "feat(postgres): add validated schema() builder param (plumbing only)"
```

### Task 3.2: Qualify every table reference with `tablePrefix`

**Files:** Modify `PostgresEntityStorage.java` (all SQL); rely on contract + new isolation test.

**Step 1 — failing test** (the real proof — two storages, one DataSource, two schemas):

```java
// in PostgresSchemaScopeTest, add (Docker-gated):
@org.junit.jupiter.api.Test
@org.junit.jupiter.api.extension.ExtendWith(uk.codery.jclaim.storage.postgres.support.RequiresDockerCondition.class)
void twoSchemasOnOneDataSourceAreIsolated() {
    javax.sql.DataSource shared = PostgresTestSupport.sharedDataSource();   // see Task 3.3
    var customers = PostgresEntityStorage.builder(shared).schema("customer").build();
    var vehicles  = PostgresEntityStorage.builder(shared).schema("vehicle").build();

    var src = uk.codery.jclaim.model.SourceSystem.of("crm");
    var alias = uk.codery.jclaim.model.Alias.of(src, "shared-id");
    var cEntity = uk.codery.jclaim.model.Entity.mint(
            uk.codery.jclaim.model.EntityId.of("acme","customer",java.util.UUID.randomUUID()),
            null, java.util.List.of(alias), java.util.List.of(), java.time.Instant.now());
    var vEntity = uk.codery.jclaim.model.Entity.mint(
            uk.codery.jclaim.model.EntityId.of("acme","vehicle",java.util.UUID.randomUUID()),
            null, java.util.List.of(alias), java.util.List.of(), java.time.Instant.now());

    customers.resolveOrCreate(alias, () -> cEntity);
    vehicles.resolveOrCreate(alias, () -> vEntity);

    org.assertj.core.api.Assertions.assertThat(customers.findByAlias(alias)).map(e -> e.id()).contains(cEntity.id());
    org.assertj.core.api.Assertions.assertThat(vehicles.findByAlias(alias)).map(e -> e.id()).contains(vEntity.id());
}
```

> Verify the real `Entity` factory signature (`Entity.mint(...)` or a builder) from `model/Entity.java` and adjust. The point of the test is: the same alias persisted under two schemas does not collide and each storage reads back only its own row.

**Step 2 — run, expect fail:** without qualification both writes target the same (default-schema) tables → unique-violation or cross-read. Expect FAIL.

**Step 3 — implement.** Introduce instance fields built from `tablePrefix` and use them in every SQL string. Replace the literal table names:

```java
private final String tEntities;     // tablePrefix + "entities"
private final String tAliases;      // tablePrefix + "entity_aliases"
private final String tAttributes;   // tablePrefix + "entity_attributes"
```

Set them in the ctor after `tablePrefix`. Then convert each SQL constant/string from a `static final String` literal to an instance method or `String.format`/concatenation using these fields. Touch every site (verified list):
- `findEntityUrnForAlias`: `"SELECT entity_urn FROM " + tAliases + " WHERE source = ? AND source_id = ?"`
- `insertEntityRow`: `"INSERT INTO " + tEntities + " (...) VALUES (?,?,?,?,?)"`
- `insertAliasRow`: `"INSERT INTO " + tAliases + " (...) VALUES (?,?,?,?,?)"`
- `insertAttributes`, `loadEntity`, `entityExists`, `nextAliasPosition`, `handleUniqueViolation`, and every other `FROM entities|entity_aliases|entity_attributes` site. **Grep to find them all:** `grep -nE "entities|entity_aliases|entity_attributes" PostgresEntityStorage.java`.

> Query *semantics* are unchanged — only the table token is prefixed. The atomic INSERT-guarded-by-PK logic in `resolveOrCreate`/`addAlias` is identical.

**Step 4 — run, expect pass:** `-Dtest=PostgresSchemaScopeTest` (isolation) + `-Dtest=PostgresEntityStorageContractTest` (unqualified path still works because prefix is `""`) → PASS.

**Step 5 — commit:**
```bash
git commit -am "feat(postgres): schema-qualify all table references via tablePrefix"
```

### Task 3.3: Provision the schema (`CREATE SCHEMA IF NOT EXISTS`) + DDL into it

**Files:** Modify `PostgresEntityStorage.applySchema()`; add `PostgresTestSupport.sharedDataSource()`.

**Step 1 — failing test:** the Task 3.2 isolation test currently relies on schemas pre-existing. Make `applySchema()` create them. Add to `PostgresTestSupport`:

```java
/** A single shared DataSource (no currentSchema) for multi-schema isolation tests. */
public static DataSource sharedDataSource() {
    ensureInitialised();
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(jdbcUrl); ds.setUser(username); ds.setPassword(password);
    return ds;   // no setCurrentSchema → adapter must create + qualify itself
}
```

Remove the pre-`createSchema` dependency from the isolation test (let the adapter do it via `applySchema(true)`, which is the default).

**Step 2 — run, expect fail:** the qualified `CREATE TABLE` runs but the schema doesn't exist → `SQLException: schema "customer" does not exist`. Expect FAIL.

**Step 3 — implement.** In `applySchema()`, when `schema != null`, first run `CREATE SCHEMA IF NOT EXISTS "<schema>"`, then apply the DDL **qualified into that schema**. Two clean options — pick the one matching how `schema.sql` is written:
- If `schema.sql` uses bare `CREATE TABLE IF NOT EXISTS entities …`: set `search_path` for the DDL connection only — `stmt.execute("SET search_path TO \"" + schema + "\"")` right after `CREATE SCHEMA`, before the statements. This is safe here because it's a **dedicated, short-lived DDL connection** that is closed immediately (not pooled, not transactional) — none of the Addendum-C runtime hazards apply to one-shot DDL.
- If you prefer no `search_path` at all: textually qualify the DDL too (`ddl.replace("CREATE TABLE IF NOT EXISTS ", "CREATE TABLE IF NOT EXISTS " + tablePrefix)` etc.) — more brittle; prefer the dedicated-connection `SET search_path` for DDL only.

```java
private void applySchema() {
    String ddl = loadSchemaSql();
    try (Connection conn = dataSource.getConnection();
         Statement stmt = conn.createStatement()) {
        if (schema != null) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
            stmt.execute("SET search_path TO \"" + schema + "\"");   // DDL-only, this connection only
        }
        for (String statement : splitStatements(ddl)) {
            if (!statement.isBlank()) {
                stmt.execute(statement);
            }
        }
    } catch (SQLException ex) {
        throw new PostgresStorageException("Failed to apply schema.sql", ex);
    }
}
```

**Step 4 — run, expect pass:** isolation test + contract test → PASS.

**Step 5 — commit:**
```bash
git commit -am "feat(postgres): create + provision per-type schema on construction"
```

### Task 3.4: Phase-3 reactor gate

`mvn -pl jclaim-storage-postgres test` then `mvn -T1C test` → `BUILD SUCCESS`. Confirm the **unqualified** contract path is still green (single-type behaviour unchanged).

---

# Phase 4 — Starter: `entity-types` properties + strict mode conditionality

### Task 4.1: `EntityType` nested properties + the `entityTypes` map

**Files:**
- Modify: `jclaim-spring-boot-starter/.../JclaimProperties.java`
- Test: `jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/JclaimPropertiesTest.java`

**Step 1 — failing test** (bind a YAML/Map and read it back). Use `Binder` against a `MapConfigurationPropertySource`:

```java
@Test
void bindsEntityTypesMap() {
    var source = new java.util.HashMap<String, Object>();
    source.put("jclaim.urn.namespace", "acme");
    source.put("jclaim.entity-types.customer.human-id.template", "CU-????-?");
    source.put("jclaim.entity-types.customer.matching.spec", "matching/customer.yaml");
    source.put("jclaim.entity-types.vehicle.storage.schema", "veh");
    source.put("jclaim.entity-types.vehicle.storage.datasource", "vehicleDs");

    var props = new org.springframework.boot.context.properties.bind.Binder(
            new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(source))
            .bind("jclaim", JclaimProperties.class)
            .orElseGet(JclaimProperties::defaults);

    assertThat(props.entityTypes()).containsKeys("customer", "vehicle");
    assertThat(props.entityTypes().get("customer").humanId().template()).isEqualTo("CU-????-?");
    assertThat(props.entityTypes().get("customer").matching().spec()).isEqualTo("matching/customer.yaml");
    assertThat(props.entityTypes().get("vehicle").storage().schema()).isEqualTo("veh");
    assertThat(props.entityTypes().get("vehicle").storage().datasource()).isEqualTo("vehicleDs");
}
```

**Step 2 — run, expect fail:** `-Dtest=JclaimPropertiesTest#bindsEntityTypesMap` → FAIL.

**Step 3 — implement.** Add to `JclaimProperties`:
- field `private Map<String, EntityType> entityTypes = new LinkedHashMap<>();` + getter `entityTypes()` + setter.
- nested `public static class EntityType` with `Urn urn` (reuse existing `Urn`, but only `namespace` is meaningful — document type comes from the key), `HumanId humanId`, `Matching matching`, and a new `EntityTypeStorage storage`.
- nested `public static class EntityTypeStorage { private String schema; private String collectionName; private String datasource; private String mongoClient; … getters/setters }` (per-type scope name overrides + per-type connection bean names).

> `EntityType` does **not** carry `type` — the map key is the type (design §1).

**Step 4 — run, expect pass.** **Step 5 — commit:**
```bash
git commit -am "feat(starter): bind jclaim.entity-types keyed map"
```

### Task 4.2: Mode-exclusivity condition

A custom `@Conditional` that matches when `jclaim.entity-types` has at least one key. Single-type beans (`jclaimResolver`, single metrics/health) gate on its **absence**; the multi-type registrar gates on its **presence**.

**Files:**
- Create: `jclaim-spring-boot-starter/.../EntityTypesConfiguredCondition.java`
- Test: `…/spring/EntityTypesConfiguredConditionTest.java`

**Step 1 — failing test** (slice test asserting bean presence). Use `ApplicationContextRunner`:

```java
@Test
void singleTypeResolverPresentWhenNoEntityTypes() {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(JclaimAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).hasBean("jclaimResolver"));
}

@Test
void singleTypeResolverAbsentWhenEntityTypesPresent() {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(JclaimAutoConfiguration.class))
        .withPropertyValues("jclaim.entity-types.customer.human-id.template=????-?")
        .run(ctx -> assertThat(ctx).doesNotHaveBean("jclaimResolver"));
}
```

**Step 2 — run, expect fail:** second assertion fails (jclaimResolver still built).

**Step 3 — implement.** Create the condition:

```java
final class EntityTypesConfiguredCondition extends org.springframework.boot.autoconfigure.condition.SpringBootCondition {
    @Override
    public org.springframework.boot.autoconfigure.condition.ConditionOutcome getMatchOutcome(
            org.springframework.context.annotation.ConditionContext context,
            org.springframework.core.type.AnnotatedTypeMetadata metadata) {
        var binder = org.springframework.boot.context.properties.bind.Binder.get(context.getEnvironment());
        boolean present = binder.bind("jclaim.entity-types",
                org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, Object.class))
                .map(m -> !m.isEmpty()).orElse(false);
        return new org.springframework.boot.autoconfigure.condition.ConditionOutcome(
                present, "jclaim.entity-types " + (present ? "present" : "absent"));
    }
}
```

Annotate the existing `@Bean("jclaimResolver")` method with `@Conditional(NotEntityTypesConfiguredCondition.class)` (add a trivial negating subclass, or invert with a flag). Simplest: create both `EntityTypesConfiguredCondition` and `NoEntityTypesCondition` (the negation) and apply the right one to single-type vs multi-type beans.

**Step 4 — run, expect pass.** **Step 5 — commit:**
```bash
git commit -am "feat(starter): suppress single-type resolver when entity-types configured"
```

---

# Phase 5 — Dynamic per-type resolver registration + facade

### Task 5.1: `EntityTypeResolverRegistrar` registers one resolver bean per type

A `BeanDefinitionRegistryPostProcessor` that binds `jclaim.entity-types`, and per type registers an `EntityResolver` bean named `jclaimEntityResolver_<type>` with an `AutowireCandidateQualifier` of `<type>`, **resolving the connection lazily** (Addendum H.1).

**Files:**
- Create: `jclaim-spring-boot-starter/.../EntityTypeResolverRegistrar.java`
- Modify: `JclaimAutoConfiguration` (register the registrar under the presence condition)
- Test: `…/spring/MultiTypeWiringTest.java`

**Step 1 — failing test** (in-memory backend, two types, assert qualified injection + facade):

```java
@Test
void registersQualifiedResolverPerType() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class))
        .withPropertyValues(
            "jclaim.urn.namespace=acme",
            "jclaim.storage.type=in-memory",
            "jclaim.entity-types.customer.human-id.template=CU-????-?",
            "jclaim.entity-types.vehicle.human-id.template=VH-????-?")
        .run(ctx -> {
            assertThat(ctx).hasBean("jclaimEntityResolver_customer");
            assertThat(ctx).hasBean("jclaimEntityResolver_vehicle");
            EntityResolver customer = ctx.getBeanProvider(EntityResolver.class)
                    .stream().toList().get(0);  // or resolve via qualifier helper below
            var resolvers = ctx.getBean(EntityResolvers.class);
            assertThat(resolvers.types()).containsExactlyInAnyOrder("customer", "vehicle");
            // The customer resolver mints customer-typed URNs.
            var minted = resolvers.forType("customer")
                    .resolveOrMint(Claim.of(SourceSystem.of("crm"), "c1")).entity();
            assertThat(minted.id().type()).isEqualTo("customer");
            assertThat(minted.id().namespace()).isEqualTo("acme");
        });
}

@Test
void qualifierInjectionResolvesByTypeKey() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class))
        .withPropertyValues(
            "jclaim.storage.type=in-memory",
            "jclaim.entity-types.customer.human-id.template=CU-????-?")
        .withUserConfiguration(QualifierProbe.class)
        .run(ctx -> assertThat(ctx.getBean(QualifierProbe.class).customer).isNotNull());
}

static class QualifierProbe {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("customer")
    EntityResolver customer;
}
```

**Step 2 — run, expect fail:** beans missing.

**Step 3 — implement.** Create the registrar. Key points:
- Implement `BeanDefinitionRegistryPostProcessor` + `EnvironmentAware`.
- In `postProcessBeanDefinitionRegistry`, bind `jclaim.entity-types` (keys only needed here) and the top-level defaults (`jclaim.urn.namespace`, `jclaim.matching.max-candidates`, storage kind).
- For each type, build a `RootBeanDefinition` for `EntityResolver` with an **instance supplier** that resolves collaborators from the `BeanFactory` at creation time (lazy — Addendum H.1):

```java
String beanName = "jclaimEntityResolver_" + type;
var bd = new org.springframework.beans.factory.support.RootBeanDefinition(EntityResolver.class);
bd.setInstanceSupplier(() -> buildResolver(beanFactory, type, entry, defaults));
bd.addQualifier(new org.springframework.beans.factory.support.AutowireCandidateQualifier(
        org.springframework.beans.factory.annotation.Qualifier.class, type));
registry.registerBeanDefinition(beanName, bd);
```

- `buildResolver(...)` (runs lazily): resolve `MatchEventSink` from the factory; build the per-type `MatchingPolicy` (if `matching.spec` set → `JspecMatchingPolicy.fromResource` via reflection-free direct call **only if** the module is present — mirror `JclaimMatchingConfiguration`'s `@ConditionalOnClass`; if absent and a spec is set, throw the same actionable message); build the **scoped storage** for the configured backend kind:
  - `in-memory` → `new InMemoryEntityStorage()`
  - `postgres` → resolve `DataSource` (per-type `storage.datasource` bean name if set, else the primary `DataSource`); `PostgresEntityStorage.builder(ds).schema(scopeName).applySchema(<from props>).build()`
  - `mongo` → resolve `MongoClient` (per-type `storage.mongo-client` if set, else primary); `client.getDatabase(db).getCollection(scopeName); MongoEntityStorage.builder(collection)...build()`
  - `scopeName` = `entityTypes.<type>.storage.schema|collectionName` if set, else the **type key**.
- Build the resolver: `DefaultEntityResolver.builder(storage).namespace(nsForType).entityType(type).humanIdTemplate(tpl).matchingPolicy(policy).maxCandidates(maxForType).matchEventSink(sink).build()`.
- `nsForType` = `entityTypes.<type>.urn.namespace` if set else top-level namespace; `maxForType` = per-type `matching.max-candidates` if set else top-level.

> Register the registrar as a `static @Bean` in `JclaimAutoConfiguration` gated by `@Conditional(EntityTypesConfiguredCondition.class)`. A `BeanDefinitionRegistryPostProcessor` bean factory method must be `static` so it runs early.

**Step 4 — run, expect pass.** **Step 5 — commit:**
```bash
git commit -am "feat(starter): dynamically register per-type resolver beans (prefixed name + type qualifier)"
```

### Task 5.2: `EntityResolvers` facade bean

**Files:** Modify `JclaimAutoConfiguration` (or the registrar) to register an `EntityResolvers` bean aggregating the per-type resolvers; extend `MultiTypeWiringTest` (already asserts `ctx.getBean(EntityResolvers.class)`).

**Step 1:** the `qualifierInjectionResolvesByTypeKey`/`registersQualifiedResolverPerType` tests already require `EntityResolvers`. If not yet present, they fail at `ctx.getBean(EntityResolvers.class)`.

**Step 3 — implement.** Add a `@Bean @Conditional(EntityTypesConfiguredCondition.class) EntityResolvers jclaimResolvers(ConfigurableListableBeanFactory bf)` that collects all `jclaimEntityResolver_*` beans into a `Map<String,EntityResolver>` (strip the prefix to recover the type key) and returns `EntityResolvers.of(map)`. Resolve them lazily via `bf.getBeansOfType(EntityResolver.class)` filtered by bean-name prefix, or track the type list in the registrar and inject by name.

**Step 4 — run, expect pass.** **Step 5 — commit:**
```bash
git commit -am "feat(starter): expose EntityResolvers facade bean in multi-type mode"
```

### Task 5.3: Failure modes — unknown type, scope collision, missing per-type connection

**Files:** extend `MultiTypeWiringTest` (+ maybe `EntityTypeResolverRegistrar`).

Tests:
- `forType("nope")` → `IllegalArgumentException` listing known types (already covered in core; add a context-level assertion).
- **Scope collision:** two types resolving to the same schema/collection on the same connection → context **fails to start**. Implement in the registrar: track `(connectionBeanName-or-default, resolvedScope)`; on duplicate, throw `BeanDefinitionStoreException` (or fail in the supplier — prefer registration-time detection where the scope is statically known).
- **Missing per-type datasource bean:** `storage.datasource=ghost` with no such bean → clear startup error naming the type + bean (the lazy supplier resolves it and throws `NoSuchBeanDefinitionException`; wrap with a message).
- **`matching.spec` set but jspec module absent:** same actionable message as single-type, per type.

```java
@Test
void scopeCollisionFailsStartup() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class))
        .withPropertyValues(
            "jclaim.storage.type=in-memory",
            "jclaim.entity-types.customer.storage.schema=shared",
            "jclaim.entity-types.vehicle.storage.schema=shared")
        .run(ctx -> assertThat(ctx).hasFailed());
}
```

> For in-memory, each type gets its own instance, so a "collision" is only meaningful for shared DB connections — but detect the configured-scope clash regardless (two types naming the same schema on the same datasource bean) since that is always a misconfiguration.

**Step 5 — commit:**
```bash
git commit -am "feat(starter): fail fast on scope collisions and missing per-type connections"
```

### Task 5.4: Phase-5 reactor gate

`mvn -pl jclaim-spring-boot-starter test` → `BUILD SUCCESS`, then `mvn -T1C test`.

---

# Phase 6 — Per-type metrics & health

### Task 6.1: Per-type metered resolver (no `@Primary` in multi-type mode)

Wrap each typed resolver with a `MeteredEntityResolver` variant that tags `type=<entity-type>`. Crucially, **do not** register a `@Primary EntityResolver` (Addendum E) — selection stays via qualifier/facade.

**Files:**
- Modify: `MeteredEntityResolver` → add a `type` tag to its meters (new ctor `MeteredEntityResolver(EntityResolver, MeterRegistry, String type)`; keep the 2-arg ctor delegating with `type=null`/untagged for single-type back-compat).
- Modify/Create: metrics wiring that decorates each `jclaimEntityResolver_<type>` bean in multi-type mode (a `BeanPostProcessor` or registrar-side decoration).
- Test: `…/spring/metrics/MultiTypeMetricsTest.java`

**Step 1 — failing test:** resolve via two types, assert `jclaim.resolve` counter carries distinct `type` tags. Use a `SimpleMeterRegistry`.

**Step 3 — implement.** Add the tag to `MeteredEntityResolver` meter builders (`.tag("type", type)` when non-null). Decorate per-type beans: simplest is for the **registrar's `buildResolver`** to wrap the resolver in `MeteredEntityResolver` when a `MeterRegistry` bean exists and metrics enabled — but the registrar runs before beans exist, so instead add a `BeanPostProcessor` that, for bean names matching `jclaimEntityResolver_*`, wraps with a type-tagged metered resolver if a `MeterRegistry` is present. Gate the whole thing on `jclaim.metrics.enabled` + `MeterRegistry` on classpath, mirroring `JclaimMetricsAutoConfiguration`.

> Keep the existing single-type `JclaimMetricsAutoConfiguration` untouched but ensure its `@ConditionalOnBean(name="jclaimResolver")` keeps it inert in multi-type mode (jclaimResolver is absent there — already true).

**Step 5 — commit:**
```bash
git commit -am "feat(starter): per-type resolver metrics with type tag (no @Primary)"
```

### Task 6.2: Per-type health contributor

One `JclaimHealthIndicator` per type, registered as `jclaimHealthIndicator_<type>` (prefix discipline — Addendum H.3), each probing its own storage scope.

**Files:**
- Modify: health auto-config to register per-type indicators in multi-type mode (the registrar tracks each type's `EntityStorage`; expose them or rebuild indicators from the typed resolvers' storages).
- Test: `…/spring/health/MultiTypeHealthTest.java` — assert one `HealthIndicator` bean per type, each `UP`.

> The current `JclaimHealthIndicator` needs an `EntityStorage`. Either expose each per-type storage as a bean (`jclaimEntityStorage_<type>`) from the registrar, or add a resolver-backed probe. Simplest: have the registrar also register each scoped `EntityStorage` as `jclaimEntityStorage_<type>`, and the health auto-config builds one indicator per such bean.

**Step 5 — commit:**
```bash
git commit -am "feat(starter): per-type health contributors"
```

### Task 6.3: Phase-6 reactor gate

`mvn -T1C test` → `BUILD SUCCESS`.

---

# Phase 7 — Single-type regression lock + cross-cutting checks

### Task 7.1: Single-type regression lock (Addendum H.2)

**Files:** `…/spring/SingleTypeRegressionTest.java`

Assert that with **no** `entity-types` the context is byte-for-behaviour identical to today:
- bean `jclaimResolver` present; no `jclaimEntityResolver_*` beans; no `EntityResolvers` bean.
- metrics: `@Primary` metered resolver present (existing behaviour).
- health: single `jclaimHealthIndicator` present.
- minted URN uses `jclaim.urn.type` (default `entity`).

**Step 5 — commit:**
```bash
git commit -am "test(starter): lock single-type behaviour against multi-type machinery"
```

### Task 7.2: End-to-end multi-type Postgres slice (Docker)

**Files:** `…/spring/MultiTypePostgresIntegrationTest.java` (`@ExtendWith(RequiresDockerCondition.class)` / Testcontainers JDBC).

Boot a context with `jclaim.storage.type=postgres`, a Testcontainers `DataSource` bean, two types, resolve the same alias under each, assert independent canonical IDs and that each type's rows live in its own schema. This is the full-stack proof that schema-per-type + registrar + facade compose.

**Step 5 — commit:**
```bash
git commit -am "test(starter): end-to-end multi-type Postgres isolation slice"
```

### Task 7.3: Phase-7 reactor gate

`mvn -T1C test` → `BUILD SUCCESS`. Verify JaCoCo line+branch ≥ 80% in every touched module.

---

# Phase 8 — Documentation

### Task 8.1: Starter README — resolver selection + per-type humanId semantics

**Files:** Modify `jclaim-spring-boot-starter/README.md` (or the root README's starter section).

Document: the `jclaim.entity-types.<type>` map, inheritance rules (only `namespace` + `matching.max-candidates` inherit), qualified-bean vs `EntityResolvers` selection, the per-type-own-connection escape hatch, and **per-scope humanId uniqueness** (Addendum D — two types may legally mint the same humanId; prefer type-specific templates like `CU-…`/`VH-…` for spoken/displayed IDs).

**Step 5 — commit:**
```bash
git commit -am "docs(starter): document multi-entity-type config, selection, and humanId scoping"
```

### Task 8.2: Update root `CLAUDE.md` Project Status + URN scheme section

**Files:** Modify `CLAUDE.md`.

Flip the planned "multiple entity types" extension to delivered; update the URN-scheme section's note that multi-type is now implemented (was "planned, additive"); point `docs/plans/2026-06-04-multi-entity-type-direction.md` readers to the realised design + this plan.

**Step 5 — commit:**
```bash
git commit -am "docs: mark multiple-entity-types milestone delivered in CLAUDE.md"
```

### Task 8.3: Optional — multi-type QuickStart example

If time allows and it adds clarity, add a `MultiTypeQuickStart` under `examples/` reconciling two types over in-memory storage, pinned by a test like the existing `*QuickStartTest`s. YAGNI: skip if the README example suffices.

---

## Final acceptance

- `mvn clean install` (full reactor, tests + JaCoCo gate) → `BUILD SUCCESS`.
- Single-type path provably unchanged (Task 7.1).
- Multi-type: qualified beans + `EntityResolvers` facade resolve; same alias/humanId isolated across types on a shared connection for **all three** backends; startup fails on scope collision, missing per-type connection, malformed scope identifier, or spec-without-jspec.
- Then: `superpowers:finishing-a-development-branch` to open the PR.
