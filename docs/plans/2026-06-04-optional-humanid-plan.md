# Optional humanId Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: execute task-by-task via
> superpowers:subagent-driven-development; each task follows
> @superpowers:test-driven-development.

**Goal:** Make humanId opt-in — the presence of a template drives it; with no
template a resolver mints entities with **no** humanId (no generation, no stored
field, no index entry).

**Architecture:** `Entity.humanId` becomes nullable; the resolver builder's
default generator becomes *absent* (a `null`/blank template = no humanId); the
three storage adapters allow absent humanIds via a **partial unique index** so
they don't collide on null; the starter's `jclaim.human-id.template` defaults to
*none*. This **flips PR #6's always-on default** to presence-driven.

**Tech Stack:** Java 21, Maven multi-module, JUnit 5 + AssertJ, Testcontainers
(Mongo + Postgres), Spring Boot 3.5 starter.

**Design reference:** `docs/plans/2026-06-04-optional-humanid-direction.md`

**Coverage requirement (Chris, this PR):** changed code must hit **≥80% on BOTH
lines and branches** — the pom's JaCoCo gate only checks lines today. Task 8
verifies branches and adds a branch gate.

**Verified current code (exact):**
- `Entity` (jclaim-core/.../model/Entity.java:17-40): record with
  `String humanId`; compact constructor `Objects.requireNonNull(humanId)` +
  `if (humanId.isBlank()) throw`.
- `DefaultEntityResolver`: constructor `requireNonNull(b.humanIdGenerator)`;
  `mintEntity` (227-240) calls `freshHumanId()` always; `freshHumanId()`
  (242-252); Builder default `humanIdGenerator = new HumanIdGenerator()` (311);
  `humanIdTemplate(String)` (348-351) → `humanIdFormat(...)` (343-346) →
  `new HumanIdGenerator(format)`.
- `InMemoryEntityStorage`: `byHumanId` map (37); in `resolveOrCreate` a
  `containsKey` collision throw + a `byHumanId.put(...)` (88-99);
  `findByHumanId` (47-52).
- Postgres `schema.sql`: `human_id text NOT NULL UNIQUE` (line 13).
  `insertEntityRow` `ps.setString(2, entity.humanId())` (388);
  `loadEntity` `rs.getString(1)` (339); `handleUniqueViolation`
  `constraint.contains("human_id")` (206).
- Mongo: `createIndexes` plain `unique(true)` on `FIELD_HUMAN_ID` (331-334);
  `toDocument` `doc.put(FIELD_HUMAN_ID, entity.humanId())` (260);
  `toEntity` `doc.getString(FIELD_HUMAN_ID)` (294); `handleDuplicateKey` matches
  `INDEX_HUMAN_ID` (157-160).
- `EntityStorageContract`: `entityWith(seed, aliases[, attrs])` helper always
  formats a humanId (585); `findByHumanId_*` + `humanIdCollision` tests
  (123-141, 222-242).
- Starter: `JclaimProperties.HumanId.template = "????-????-?"` (118);
  `JclaimAutoConfiguration` calls `.humanIdTemplate(properties.humanId().template())`
  unconditionally (67). `JclaimPropertiesTest.urnAndHumanIdDefaults` asserts the
  template default is `????-????-?`.
- pom `jacoco-check`: PACKAGE **LINE** 0.80 only (266-285).

**Test commands:**
- Core: `mvn -q -pl jclaim-core test -Dtest=ClassName`
- Postgres (Docker): `mvn -q -pl jclaim-storage-postgres -am test -Dtest=ClassName`
- Mongo (Docker): `mvn -q -pl jclaim-storage-mongo -am test -Dtest=ClassName`
- Starter: `mvn -q -pl jclaim-spring-boot-starter -am test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false`
- Full reactor (Docker): `mvn clean install`

---

## Task 1: `Entity` — nullable humanId

**Files:**
- Modify: `jclaim-core/src/main/java/uk/codery/jclaim/model/Entity.java`
- Test: `jclaim-core/src/test/java/uk/codery/jclaim/model/EntityTest.java` (locate;
  create if absent)

**Step 1 — failing tests:**
```java
@Test
void allowsNullHumanId() {
    Entity e = new Entity(EntityId.of(UUID.randomUUID()), null,
            List.of(), List.of(), null, Instant.EPOCH, Instant.EPOCH);
    assertThat(e.humanId()).isNull();
}

@Test
void rejectsBlankHumanIdWhenPresent() {
    assertThatThrownBy(() -> new Entity(EntityId.of(UUID.randomUUID()), "  ",
            List.of(), List.of(), null, Instant.EPOCH, Instant.EPOCH))
            .isInstanceOf(IllegalArgumentException.class);
}
```
Keep any existing test that builds an Entity with a real humanId.

**Step 2 — run, verify FAIL:** `mvn -q -pl jclaim-core test -Dtest=EntityTest`

**Step 3 — implement.** In the compact constructor: remove
`Objects.requireNonNull(humanId, "humanId");`, and change the blank check to
allow null:
```java
if (humanId != null && humanId.isBlank()) {
    throw new IllegalArgumentException("humanId must not be blank when present");
}
```
Update the record/field Javadoc to note humanId is nullable (absent = this
entity type mints no humanId).

**Step 4 — pass:** `mvn -q -pl jclaim-core test -Dtest=EntityTest`, then
`mvn -q -pl jclaim-core test` (whole module — expect some resolver/contract
tests to still pass since they pass non-null humanIds).

**Step 5 — commit:**
```bash
git add jclaim-core/src/main/java/uk/codery/jclaim/model/Entity.java \
        jclaim-core/src/test/java/uk/codery/jclaim/model/EntityTest.java
git commit -m "feat(core): allow nullable Entity.humanId"
```

---

## Task 2: Resolver builder — presence-driven humanId

**Files:**
- Modify: `jclaim-core/src/main/java/uk/codery/jclaim/resolver/DefaultEntityResolver.java`
- Test: `jclaim-core/src/test/java/uk/codery/jclaim/resolver/DefaultEntityResolverTest.java`

**Step 1 — failing/▸updated tests:**
```java
@Test
void noTemplate_mintsEntityWithNullHumanId() {
    EntityStorage storage = new InMemoryEntityStorage();
    EntityResolver resolver = DefaultEntityResolver.builder(storage).build(); // no humanId template
    Entity e = ((ResolutionResult.Minted) resolver.resolveOrMint(
            new Claim(SourceSystem.of("crm"), "u-1", List.of()))).entity();
    assertThat(e.humanId()).isNull();
}

@Test
void blankTemplate_mintsNoHumanId() {
    EntityStorage storage = new InMemoryEntityStorage();
    EntityResolver resolver = DefaultEntityResolver.builder(storage)
            .humanIdTemplate("  ").build();
    Entity e = ((ResolutionResult.Minted) resolver.resolveOrMint(
            new Claim(SourceSystem.of("crm"), "u-2", List.of()))).entity();
    assertThat(e.humanId()).isNull();
}
```
**Update the existing `unconfiguredResolverMintsLegacyDefaults` test** (added in
PR #6): an unconfigured resolver now mints **no** humanId. Change its humanId
assertion from the `XXXX-XXXX-X` regex to `assertThat(e.humanId()).isNull()`;
keep the `urn:codery:entity:` assertions. Keep `mintsHumanIdWithConfiguredTemplate`
(template set → humanId present) as-is.

**Step 2 — run, verify FAIL/changed:** `mvn -q -pl jclaim-core test -Dtest=DefaultEntityResolverTest`

**Step 3 — implement** in `DefaultEntityResolver`:
- Constructor: change `this.humanIdGenerator = Objects.requireNonNull(b.humanIdGenerator, "humanIdGenerator");`
  to `this.humanIdGenerator = b.humanIdGenerator;` (may be null).
- `mintEntity`: `String humanId = humanIdGenerator == null ? null : freshHumanId();`
- Builder: change the default to `private HumanIdGenerator humanIdGenerator = null;`
- Replace `humanIdTemplate` + `humanIdFormat` with a single presence-driven
  `humanIdTemplate`:
  ```java
  /** The humanId template. {@code null}/blank means this resolver mints no humanId. */
  public Builder humanIdTemplate(String template) {
      this.humanIdGenerator = (template == null || template.isBlank())
              ? null
              : new HumanIdGenerator(HumanIdFormat.ofTemplate(template));
      return this;
  }
  ```
  Remove `humanIdFormat(HumanIdFormat)`. Keep `humanIdGenerator(HumanIdGenerator)`
  as the advanced/test-entropy hook (grep first: `grep -rn "humanIdFormat(" jclaim-core jclaim-spring-boot-starter` and redirect any caller to `humanIdTemplate`).
- Keep the `HumanIdFormat` import (still used).

**Step 4 — pass:** `mvn -q -pl jclaim-core test -Dtest=DefaultEntityResolverTest`,
then `mvn -q -pl jclaim-core test`.

**Step 5 — commit:**
```bash
git add jclaim-core/src/main/java/uk/codery/jclaim/resolver/DefaultEntityResolver.java \
        jclaim-core/src/test/java/uk/codery/jclaim/resolver/DefaultEntityResolverTest.java
git commit -m "feat(core): presence-driven humanId (no template -> no humanId)"
```

---

## Task 3: `EntityStorageContract` + in-memory null handling

**Files:**
- Modify: `jclaim-core/src/test/java/uk/codery/jclaim/storage/EntityStorageContract.java`
- Modify: `jclaim-core/src/main/java/uk/codery/jclaim/storage/memory/InMemoryEntityStorage.java`

**Step 1 — add contract helper + tests** (these run against EVERY adapter):
```java
protected static Entity entityWithoutHumanId(int seed, List<Alias> aliases) {
    UUID id = UUID.fromString("00000000-0000-7000-8000-" + String.format("%012d", seed));
    return new Entity(EntityId.of(id), null, new ArrayList<>(aliases),
            List.of(MatchingAttribute.of("seed", seed)), null, NOW, NOW);
}

@Test
void resolveOrCreate_noHumanId_storesAndRoundTripsNull() {
    Entity e = entityWithoutHumanId(0, List.of(ALICE_ECOM));
    storage.resolveOrCreate(ALICE_ECOM, () -> e);
    assertThat(storage.findByUrn(e.id()).orElseThrow().humanId()).isNull();
}

@Test
void resolveOrCreate_multipleEntitiesWithoutHumanId_coexist() {
    storage.resolveOrCreate(ALICE_ECOM, () -> entityWithoutHumanId(0, List.of(ALICE_ECOM)));
    storage.resolveOrCreate(BOB_ECOM, () -> entityWithoutHumanId(1, List.of(BOB_ECOM)));
    assertThat(storage.findByAlias(ALICE_ECOM)).isPresent();
    assertThat(storage.findByAlias(BOB_ECOM)).isPresent();
}
```

**Step 2 — run in-memory, verify the new tests FAIL** (current in-memory NPEs/puts
null): `mvn -q -pl jclaim-core test -Dtest=InMemoryEntityStorageTest`

**Step 3 — implement in-memory null handling.** In `resolveOrCreate`, guard both
the collision check and the index put with a non-null check:
```java
if (minted.humanId() != null && byHumanId.containsKey(minted.humanId())) {
    throw new IllegalStateException("humanId collision on mint: " + minted.humanId());
}
// ... after the entity is stored:
if (minted.humanId() != null) {
    byHumanId.put(minted.humanId(), minted.id());
}
```
(Match the exact surrounding structure at lines 88-99.) Leave `findByHumanId`
unchanged (still `requireNonNull` on the lookup argument — looking up a null is
nonsensical).

**Step 4 — pass:** `mvn -q -pl jclaim-core test -Dtest=InMemoryEntityStorageTest`,
then `mvn -q -pl jclaim-core test` (whole module green; the existing
humanId-present tests still pass).

**Step 5 — commit:**
```bash
git add jclaim-core/src/test/java/uk/codery/jclaim/storage/EntityStorageContract.java \
        jclaim-core/src/main/java/uk/codery/jclaim/storage/memory/InMemoryEntityStorage.java
git commit -m "feat(core): in-memory storage allows absent humanId; contract cases"
```

---

## Task 4: Postgres adapter — nullable humanId + partial unique index

**Files:**
- Modify: `jclaim-storage-postgres/src/main/resources/uk/codery/jclaim/storage/postgres/schema.sql`
- Possibly modify: `jclaim-storage-postgres/.../PostgresEntityStorage.java`

**Step 1 — the contract suite already provides the failing tests** (Task 3's two
new cases). Run them RED against Postgres (Docker required):
`mvn -q -pl jclaim-storage-postgres -am test -Dtest=PostgresEntityStorageContractTest`
Expect failure: `human_id text NOT NULL` rejects the null insert. (If Docker is
unavailable, say so and proceed on reasoning.)

**Step 2 — implement.** In `schema.sql`, make the column nullable and replace the
inline `UNIQUE` with a **partial unique index** (name MUST contain `human_id` so
`handleUniqueViolation`'s `constraint.contains("human_id")` still fires):
```sql
CREATE TABLE IF NOT EXISTS entities (
    urn            text PRIMARY KEY,
    human_id       text NULL,
    superseded_by  text NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS entities_human_id_unique
    ON entities (human_id) WHERE human_id IS NOT NULL;
```
`insertEntityRow` (`ps.setString(2, entity.humanId())`) and `loadEntity`
(`rs.getString(1)`) already handle null via JDBC — leave them unless a test shows
otherwise. Confirm `handleUniqueViolation` still recognises the humanId
collision (the index name contains `human_id`).

**Step 3 — pass (Docker):**
`mvn -q -pl jclaim-storage-postgres -am test -Dtest=PostgresEntityStorageContractTest`
— all green, including the existing humanId-collision test (two entities that DO
share a humanId still conflict).

**Step 4 — commit:**
```bash
git add jclaim-storage-postgres/src/main/resources/uk/codery/jclaim/storage/postgres/schema.sql
# plus PostgresEntityStorage.java only if you had to change it
git commit -m "feat(storage-postgres): nullable human_id + partial unique index"
```

---

## Task 5: Mongo adapter — partial unique index + omit absent field

**Files:**
- Modify: `jclaim-storage-mongo/src/main/java/uk/codery/jclaim/storage/mongo/MongoEntityStorage.java`

**Step 1 — run the contract suite RED against Mongo** (Docker):
`mvn -q -pl jclaim-storage-mongo -am test -Dtest=MongoEntityStorageContractTest`
Expect failure: the plain unique index + `doc.put(humanId, null)` makes the two
null-humanId entities collide on null.

**Step 2 — implement two changes:**
1. `createIndexes` — make the unique index **partial** so absent-field docs are
   not indexed:
   ```java
   collection.createIndex(
           Indexes.ascending(FIELD_HUMAN_ID),
           new IndexOptions().unique(true)
                   .partialFilterExpression(Filters.exists(FIELD_HUMAN_ID, true))
                   .name(INDEX_HUMAN_ID));
   ```
   (Import `com.mongodb.client.model.Filters` if not already present.)
2. `toDocument` — **omit** the field when null (a present-but-`null` value would
   still be indexed by the `$exists:true` filter and collide):
   ```java
   if (entity.humanId() != null) {
       doc.put(FIELD_HUMAN_ID, entity.humanId());
   }
   ```
`toEntity` (`doc.getString(FIELD_HUMAN_ID)` → null when absent) and
`handleDuplicateKey` (matches `INDEX_HUMAN_ID`) need no change.

**Step 3 — pass (Docker):**
`mvn -q -pl jclaim-storage-mongo -am test -Dtest=MongoEntityStorageContractTest`
— all green.

**Step 4 — commit:**
```bash
git add jclaim-storage-mongo/src/main/java/uk/codery/jclaim/storage/mongo/MongoEntityStorage.java
git commit -m "feat(storage-mongo): partial unique humanId index; omit absent field"
```

---

## Task 6: Spring starter — default to no humanId

**Files:**
- Modify: `jclaim-spring-boot-starter/.../spring/JclaimProperties.java`
- Test: `jclaim-spring-boot-starter/.../spring/JclaimPropertiesTest.java`
- Test: `jclaim-spring-boot-starter/.../spring/JclaimAutoConfigurationTest.java`

Note: `JclaimAutoConfiguration` already calls
`.humanIdTemplate(properties.humanId().template())` unconditionally, and after
Task 2 the builder treats a null/blank template as "no humanId" — so the
**only production change** is the property default. Eager validation of a
malformed *non-blank* template is preserved.

**Step 1 — update/failing tests.**
In `JclaimPropertiesTest`, change `urnAndHumanIdDefaults` to expect a null
template default:
```java
assertThat(p.humanId().template()).isNull();
```
In `JclaimAutoConfigurationTest`, add:
```java
@Test
void noTemplateConfigured_mintsNoHumanId() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JclaimAutoConfiguration.class))
        .run(ctx -> {
            EntityResolver r = ctx.getBean(EntityResolver.class);
            Entity e = ((ResolutionResult.Minted) r.resolveOrMint(
                    new Claim(SourceSystem.of("crm"), "u-1", List.of()))).entity();
            assertThat(e.humanId()).isNull();
        });
}
```
Keep `resolverHonoursConfiguredUrnTypeAndTemplate` (template set → humanId
present) and `badHumanIdTemplateFailsStartup` (malformed non-blank → fails
startup).

**Step 2 — run, verify:**
`mvn -q -pl jclaim-spring-boot-starter -am test -Dtest=JclaimPropertiesTest,JclaimAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`

**Step 3 — implement.** In `JclaimProperties.HumanId`, drop the default:
```java
private String template;   // null → no humanId minted
```
(Adjust the Javadoc.) No change to `JclaimAutoConfiguration`.

**Step 4 — pass:**
`mvn -q -pl jclaim-spring-boot-starter -am test -Dtest=JclaimPropertiesTest,JclaimAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`

**Step 5 — commit:**
```bash
git add jclaim-spring-boot-starter/src/main/java/uk/codery/jclaim/spring/JclaimProperties.java \
        jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/JclaimPropertiesTest.java \
        jclaim-spring-boot-starter/src/test/java/uk/codery/jclaim/spring/JclaimAutoConfigurationTest.java
git commit -m "feat(starter): humanId opt-in; jclaim.human-id.template defaults to none"
```

---

## Task 7: Docs

**Files:**
- `jclaim-spring-boot-starter/README.md` — properties table: `jclaim.human-id.template`
  default → `_(none)_`, description "absent → **no humanId minted**; set a
  template to mint one (eagerly validated)." Update the "Entity type & namespace"
  and "humanId template" sections to lead with "humanId is opt-in".
- `README.md` (root) — the human-friendly ID bullet/stanza: humanId is now
  optional, minted only when a template is configured; default is none.
- `CLAUDE.md` §2 (Human-friendly IDs) — reframe: the ID is **opt-in**, driven by
  the presence of a template (`humanIdTemplate` / `jclaim.human-id.template`);
  default is no humanId; `Entity.humanId` is nullable; storage uses a partial
  unique index. Do NOT touch the brain header block.
- `CHANGELOG.md` — under Unreleased: **Changed (breaking, pre-1.0)** — humanId is
  now opt-in; `jclaim.human-id.template` defaults to none (was `????-????-?`), so
  the default config mints no humanId; `Entity.humanId` is nullable; the storage
  humanId unique index is now partial (Postgres `WHERE human_id IS NOT NULL`,
  Mongo `$exists` partial filter). Note the builder dropped `humanIdFormat(...)`.

**Step 1 — edit.** **Step 2 — sanity:** `mvn -q -pl jclaim-core test`.
**Step 3 — commit:**
```bash
git add README.md CLAUDE.md CHANGELOG.md jclaim-spring-boot-starter/README.md
git commit -m "docs: humanId is opt-in (presence-driven)"
```

---

## Task 8: Coverage — verify lines AND branches ≥80%

**Files:** test additions as needed; possibly `pom.xml`.

**Step 1 — measure.** `mvn -q clean test jacoco:report` (core needs no Docker;
run the storage/starter reports too — Docker up). For every file changed in this
PR, open `<module>/target/site/jacoco/.../<File>.html` and read **both** the LINE
and BRANCH columns. Target ≥80% on each. The likely thin spots (mirror the
Codecov report on #6): the new null-branches in `mintEntity`, the
builder's null/blank ternary, the in-memory/Mongo null guards. Add focused tests
for any uncovered branch (both sides of each new conditional).

**Step 2 — add the branch gate (cautiously).** In `pom.xml`'s `jacoco-check`
rule, add a BRANCH limit beside the LINE one:
```xml
<limit>
    <counter>BRANCH</counter>
    <value>COVEREDRATIO</value>
    <minimum>0.80</minimum>
</limit>
```
Run the **full reactor**: `mvn clean install` (Docker). If it passes, keep the
gate — it enforces branches going forward. **If a pre-existing package fails**
branch coverage (unrelated to this feature): lift it with a couple of targeted
tests if cheap; if it would balloon scope, **revert just the BRANCH `<limit>`
addition** (keep the line gate), `log()`/note which packages fall short, and
report — do not block this feature on unrelated pre-existing gaps.

**Step 3 — commit:**
```bash
git add -A
git commit -m "test: cover new humanId branches; gate branch coverage at 80%"
```

---

## Final verification

- `mvn clean install` — full reactor green (Docker up). Confirm: an unconfigured
  resolver and the zero-config starter now mint entities with **null** humanId;
  a configured template still mints + dedupes; all three adapters store and
  round-trip absent humanIds without collision; many humanId-less entities
  coexist.
- Confirm changed-file LINE **and** BRANCH coverage ≥80%.
- Final holistic review across the branch, then push + open the PR.

> **For Claude:** after all tasks, use superpowers:finishing-a-development-branch.
