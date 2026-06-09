# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `OlcPublicIdGenerator` — a vowel-resistant public-ID generator over Open
  Location Code's curated 20-symbol alphabet (`23456789CFGHJMPQRVWX`), a sibling
  to `CrockfordPublicIdGenerator`. Implements ADR-0002 item 7.
- `IdAlphabet` strategy and an alphabet-parametric `PublicIdFormat`
  (`ofTemplate(template, alphabet)`); Crockford output is unchanged.

- **`CandidatePoolTruncated` stewardship event.** Fired by `resolveOrMint`
  whenever the candidate pool returned by `findCandidates` hits the `maxCandidates`
  cap, making a possibly silently-missed match (false mint caused by truncation)
  observable to a `MatchEventSink`. The event carries `(Claim claim, int cap)`.
  Previously, truncation only produced a `WARN` log entry; it now also surfaces as
  a first-class `MatchEvent` regardless of whether the truncated call ultimately
  matched, minted, raised `MatchUndecided`, or raised `MatchAmbiguous`. The Spring
  metric `jclaim.matching.pool_truncated_total` now counts truncation across all
  outcomes via this event (previously the counter was only incremented for
  undecided/ambiguous truncated calls). Implements ADR-0001 action item 4.

### Changed (breaking, pre-1.0)

- **`PublicIdFormat.dataBits()` replaced by `dataChars()`.** Now that
  `PublicIdFormat` renders over any `IdAlphabet`, the entropy width in *bits* is
  Crockford-specific (base-20 OLC has no integer bits-per-symbol). The
  `dataBits()` accessor (which returned `dataChars × 5`) is removed in favour of
  the radix-independent `dataChars()`. Callers needing a bit count should compute
  it from the alphabet's radix.

- **`candidatePoolTruncated` removed from `MatchUndecided` and `MatchAmbiguous`.**
  The boolean field carried on those two events is gone; truncation is now reported
  exclusively by the dedicated `CandidatePoolTruncated` event. Sinks that pattern-
  matched on `candidatePoolTruncated` must be updated to handle the new event
  variant instead.

- **Human → Public vocabulary rename across the entire library.** All
  display-ID vocabulary has been renamed from `human`/`humanId`/`human-id`
  to `public`/`publicId`/`public-id`. This is a pre-1.0.0 "break once"
  clean-up applied while there are no external consumers.

  - **`PublicIdGenerator` port introduced.**
    `HumanIdGenerator` (the former concrete `final class`) is extracted into a
    `@FunctionalInterface { String generate(); }` port named `PublicIdGenerator`.
    `CrockfordPublicIdGenerator` is the built-in random Crockford+Damm
    implementation (the former class, renamed). `FilteringPublicIdGenerator` is a
    new composable decorator wrapping any `PublicIdGenerator` with a
    `Predicate<String>` acceptance gate; the default predicate is allow-all, so
    behaviour is preserved exactly. Resolver builder slot renamed
    `humanIdTemplate(...)` → `publicIdTemplate(...)`; Spring property
    `jclaim.human-id.template` → `jclaim.public-id.template`; per-type key
    `entity-types.<t>.human-id.template` → `entity-types.<t>.public-id.template`.

  - **`Entity.publicId()` (was `humanId()`).** The display-ID accessor on the
    domain record is renamed. Null behaviour and nullability are unchanged.

  - **`EntityStorage.findByPublicId` (was `findByHumanId`).** The storage port
    method is renamed. All three adapters (in-memory, Postgres, MongoDB) and the
    abstract `EntityStorageContract` conformance suite are updated.

  - **Postgres schema:** column `human_id` → `public_id`; unique partial index
    `entities_human_id_unique` → `entities_public_id_unique`.

  - **MongoDB schema:** document field `humanId` → `publicId`; unique partial
    index `jclaim_humanId_unique` → `jclaim_publicId_unique`.

  - **Migration note.** Existing deployments that use the display-ID feature
    (i.e. `human-id.template` was set) must migrate manually — no automated
    migration script is shipped:
    - Postgres: `ALTER TABLE entities RENAME COLUMN human_id TO public_id;`
      then drop `entities_human_id_unique` and recreate as
      `entities_public_id_unique`.
    - MongoDB: `db.<collection>.updateMany({ humanId: { $exists: true } }, { $rename: { "humanId": "publicId" } })`,
      then `db.<collection>.dropIndex("jclaim_humanId_unique")` and recreate via the adapter (auto-created on construction) or
      `db.<collection>.createIndex({ publicId: 1 }, { unique: true, partialFilterExpression: { publicId: { $exists: true } }, name: "jclaim_publicId_unique" })`.

## [0.2.0] - 2026-06-07

### Added

- **Blocking keys separated from scored attributes.**
  `MatchingPolicy.blockingKeys()` (default empty) lets a policy declare which
  attribute names fetch the candidate pool, distinct from the attributes it
  scores. When non-empty, `resolveOrMint` blocks on a projection of the claim
  (`Claim.projectedTo(Set<String>)`) but scores candidates against the **full**
  claim — so a weak, low-cardinality attribute can be scoring evidence without
  flooding (and truncating) the capped pool. The empty default reproduces the
  historic "block on every attribute" behaviour exactly. Core only — no
  `EntityStorage` port, adapter, or `EntityStorageContract` change. (#17)
- **Blocking keys reachable via the jspec provider and the Spring starter.**
  `JspecMatchingPolicy` gains a `blockingKeys(Collection<String>)` builder slot
  and keyed `fromResource(path, keys)` / `fromString(text, keys)` factories (the
  no-keys forms delegate, so existing call sites are unchanged; blank key names
  are rejected). The starter adds `jclaim.matching.blocking-keys` on both the
  single-type `Matching` and per-type `EntityTypeMatching` config — **per-type
  only, never inherited**, mirroring `matching.spec`. Blocking keys configured
  without a spec are ignored and logged at WARN (alias-only fall-back). (#18)

### Documentation

- **ADR-0001 — matching architecture.** Records the decision to separate
  blocking keys from scored attributes, the claim-projection mechanism, and the
  deterministic-vs-probabilistic policy taxonomy
  (`docs/adr/0001-matching-blocking-keys-and-policy-tiers.md`).

## [0.1.0] - 2026-06-05

### Added

- **Configurable URN type segment.** The URN type segment — historically
  the hard-coded literal `entity` in `urn:<namespace>:<type>:<uuid>` — is
  now configurable per resolver. `EntityId.of(namespace, type, uuid)`
  constructs an arbitrary-type URN; the two-arg
  `EntityId.of(namespace, uuid)` still defaults type to
  `EntityId.DEFAULT_TYPE` (`entity`). New `EntityId.type()` accessor.
  Resolver builder slot `DefaultEntityResolver.Builder.entityType(...)`;
  starter property `jclaim.urn.type` (default `entity`). Defaults
  reproduce prior URN output exactly.
- **Configurable humanId template.** The humanId format (e.g.
  `K7M2-9X4P-N`) is now driven by a template compiled into the new
  immutable `HumanIdFormat` value object —
  `HumanIdFormat.ofTemplate(String)`. In the template, `?` is a
  placeholder (the **last** `?` renders the Damm check digit, every other
  `?` a random Crockford Base32 data symbol) and any other character is a
  literal; 1–12 data placeholders (2–13 `?` total) keep the value within
  a 60-bit `long`. Resolver builder slot
  `DefaultEntityResolver.Builder.humanIdTemplate(String)`; starter property
  `jclaim.human-id.template`. humanId is **opt-in** — the default is no
  template, so by default no humanId is minted (see _Changed (breaking,
  pre-1.0)_); a configured non-blank template is eagerly validated — a
  malformed template fails context startup, and `????-????-?` reproduces
  the historic `XXXX-XXXX-X` shape.
- **Configurable matching policy.** `resolveOrMint` no longer mints
  blindly when an exact `(source, sourceId)` alias owner is absent. It
  blocks a candidate pool sharing an attribute with the claim and scores
  each candidate with a `MatchingPolicy`, returning a
  `TriState` (`MATCHED` / `NOT_MATCHED` / `UNDETERMINED`). The resolver
  always returns an identity; ambiguity surfaces as typed stewardship
  events. An exact alias owner still short-circuits to `Matched`,
  preserving the alias-atomic concurrency guarantee.
  - **`uk.codery.jclaim.matching` (in `jclaim-core`).** The
    `MatchingPolicy` port, the `TriState` enum, and the default
    `AliasOnlyMatchingPolicy` (`MatchingPolicy.aliasOnly()` — a
    stateless singleton matching iff the candidate already owns the
    claim's alias). Carries **no** jspec dependency. With the default
    policy, behaviour is identical to the previous alias-only matching.
  - **`jclaim-matching-jspec` — new (fifth) Maven module.** JSPEC-backed
    `MatchingPolicy` provider in package
    `uk.codery.jclaim.matching.jspec`, depending on `jclaim-core` +
    `uk.codery:jspec:0.6.0` (managed centrally in the parent POM).
    `JspecMatchingPolicy.fromResource(path)` / `.fromString(text)` /
    `.builder()` build a policy from a YAML or JSON spec. Each
    `(Claim, candidate)` pair is projected into a target document
    (`claim.*`) and context document (`candidate.*`) by a
    `DocumentProjection`; the spec evaluates via jspec's two-arg
    `evaluate(target, context)`, with operands late-binding candidate
    values through the **`$contextPath`** sentinel
    (`$eq: { $contextPath: candidate.email }`); the `EvaluationOutcome`
    collapses to a `TriState` via an `OutcomeAggregator` (default
    conjunctive). `jclaim-core` and the storage adapters keep zero jspec
    dependency — the provider isolates it, mirroring the storage
    port/adapter split.
  - **Candidate cap.** `EntityStorage.findCandidates(Claim, int limit)`
    bounds the candidate IO; the one-arg `findCandidates(Claim)`
    overload delegates to `Integer.MAX_VALUE`. Resolver builder slot
    `.maxCandidates(int)` (default 100); starter property
    `jclaim.matching.max-candidates`. Pool truncation logs at WARN and
    increments the Micrometer counter
    `jclaim.matching.pool_truncated_total`.
  - **Resolver builder slots.**
    `DefaultEntityResolver.builder(storage).namespace(...).matchingPolicy(...).matchEventSink(...).maxCandidates(...).build()`.
  - **Stewardship events for deferred resolution.** `MatchUndecided`
    (a fresh mint had only `UNDETERMINED` candidates) and
    `MatchAmbiguous` (multiple candidates `MATCHED`; winner is the
    oldest by `createdAt`, tiebreak urn — alternatives surfaced). Both
    carry `TriState` outcomes only — never jspec types, so core stays
    jspec-free.
  - **Starter matching auto-configuration.** New `jclaim.matching.spec`
    (classpath spec resource; eager-validated — a bad path fails context
    startup; requires `jclaim-matching-jspec` on the classpath) and
    `jclaim.matching.max-candidates`. With no `spec`, the policy is
    `MatchingPolicy.aliasOnly()`. Add `jclaim-matching-jspec` as an
    (optional) dependency to use a spec. The resolver bean is named
    `jclaimResolver`.
- **`jclaim-spring-boot-starter`.** Fourth Maven module — Spring Boot 3.x
  auto-configuration that wires the resolver, selects a storage adapter by
  classpath + bean presence (in-memory default, plus opt-in Mongo / Postgres),
  bridges conflict events to Spring's `ApplicationEventPublisher` as
  `JclaimConflictEvent`, and ships optional Actuator `HealthIndicator` +
  Micrometer metrics (`jclaim.resolve` counter tagged by outcome,
  `jclaim.resolve.duration` timer, `jclaim.findCandidates` counter). All
  beans are `@ConditionalOnMissingBean`. Configuration via the `jclaim.*`
  property prefix. `jclaim-core` and the storage adapters keep zero Spring
  dependencies — the starter consumes them.
- **`EntityStorage.findCandidates(Claim) -> Set<Entity>`.** Candidate
  retrieval operation: returns the union of entities whose alias graph
  contains the claim's `(source, sourceId)` and entities carrying any
  `(name, value)` attribute pair that also appears in the claim's
  attributes. Inclusive, unordered, no scoring — scoring is the future
  matching policy's job. Implemented identically across all three
  adapters and pinned to behaviour by 11 new conformance tests.
  - In-memory adapter scans `byUrn.values()` under no lock (read-only
    snapshot).
  - Mongo adapter uses a single `find({$or: [...]})` query; a new
    non-unique compound index `jclaim_attributes_lookup` on
    `(attributes.name, attributes.value)` is auto-created on startup
    alongside the existing alias / humanId unique indexes.
  - Postgres adapter uses one alias-table lookup plus one
    per-attribute name+value query against `entity_attributes`, with a
    new non-unique index `idx_entity_attributes_name_value` on
    `(name, value)` shipped in `schema.sql`.
  - Exposed on `EntityResolver` as a pure delegation to the storage
    port. Documented as an inspection API for stewardship and
    debugging; `resolveOrMint` still matches only on alias in this
    release. The future JSpec-driven matching policy session will
    plug into this candidate stream.
- **Storage adapters: MongoDB and PostgreSQL.** Two new modules ship
  drop-in `EntityStorage` adapters for production-grade durable storage:
  - `uk.codery:jclaim-storage-mongo` — single-collection adapter backed
    by a unique compound index on `(aliases.source, aliases.sourceId)`.
    Atomic `resolveOrCreate` via `insertOne` against the index; atomic
    `addAlias` via `$addToSet`. Indexes auto-created on startup;
    opt-out via `.createIndexes(false)`.
  - `uk.codery:jclaim-storage-postgres` — plain-JDBC adapter against a
    normalised three-table schema (`entities`, `entity_aliases`,
    `entity_attributes`). Atomic `resolveOrCreate` via transactional
    INSERT guarded by a primary key on `(source, source_id)`; atomic
    `addAlias` via single-row INSERT. Schema auto-applied on startup
    from a classpath `schema.sql`; opt-out via `.applySchema(false)`.
    No Spring, no JPA, no JDBI — plain JDBC by design.
- **`EntityStorageContract` abstract test suite.** Pins every adapter
  (in-memory, Mongo, Postgres) to identical behaviour: 22 conformance
  tests cover lookup, mint / match outcomes, factory-invocation
  semantics, alias collisions, idempotency, and concurrent
  `resolveOrCreate` on the same alias. Ships in `jclaim-core`'s
  tests-classifier jar so adapter modules consume it via
  `<type>test-jar</type>`.
- **Corpus reconciliation contracts.** The retail, product, and
  property reconciliation tests are now abstract base classes
  parameterised by storage factory. Every adapter runs the same
  ~12 reconciliation tests against the same YAML corpora; identical
  entity graphs across all three backends are part of the test
  contract.
- **`maven-jar-plugin` registered in parent pluginManagement** so any
  module can attach a `test-jar` classifier output with a single
  execution stanza.
- **`maven-failsafe-plugin` registered in parent pluginManagement** for
  modules that prefer to run their integration tests under
  `failsafe:integration-test` rather than `surefire:test`.
- Centralised dependency versions in the parent POM for
  `mongodb-driver-sync` (5.2.0), `postgresql` (42.7.4), and the
  `testcontainers-bom` (1.21.3, imported as scope=import).

### Changed (breaking, pre-1.0)

- **humanId is now opt-in (presence-driven).** `jclaim.human-id.template`
  defaults to **none** (was `????-????-?`), so the default configuration
  mints **no** humanId — no generation, no stored field, no index entry.
  Provide a template to opt in. `Entity.humanId` is now **nullable**. The
  resolver builder dropped `humanIdFormat(HumanIdFormat)` — use
  `humanIdTemplate(String)` (null/blank → no humanId). A malformed
  *non-blank* template still throws eagerly. This flips the previous
  default, which always minted `????-????-?`.
- **Storage humanId unique index is now partial.** It covers only entities
  that have a humanId:
  - Postgres — `human_id` is nullable (`human_id text NULL`) and the
    constraint is a partial unique index
    `entities_human_id_unique ON entities (human_id) WHERE human_id IS NOT NULL`.
  - Mongo — a partial unique index with
    `partialFilterExpression {humanId: {$exists: true}}`; the `humanId`
    field is **omitted** from the document when absent (a present-but-null
    value would still satisfy `$exists` and collide).
  - In-memory — the humanId index skips null keys.
- **Migration (existing SNAPSHOT deployments only; nothing is released).**
  - Postgres — make the column nullable, drop the old unique constraint,
    and create the partial index:
    ```sql
    ALTER TABLE entities ALTER COLUMN human_id DROP NOT NULL;
    -- drop the old (full) unique constraint/index, then:
    CREATE UNIQUE INDEX entities_human_id_unique
        ON entities (human_id) WHERE human_id IS NOT NULL;
    ```
  - Mongo — the new partial index reuses the name `jclaim_humanId_unique`
    with different options, so drop the old one first; the adapter
    recreates it as partial on next startup:
    ```js
    db.<collection>.dropIndex("jclaim_humanId_unique")
    ```
- **Starter: `jclaim.namespace` → `jclaim.urn.namespace`.** The URN
  namespace property moves under the new `jclaim.urn.*` group (alongside
  the new `jclaim.urn.type`); default unchanged (`codery`). Safe because
  no artefact has been published.
- **`ConflictEventSink` → `MatchEventSink`.** The event sink interface
  is renamed; its single method now accepts the new sealed `MatchEvent`
  supertype. Default factory `MatchEventSink.noop()`. Resolver builder
  slot renamed `conflictSink(...)` → `matchEventSink(...)`.
- **Sealed `MatchEvent` hierarchy.** `MatchEvent permits
  EntityAttributesConflicted, MatchUndecided, MatchAmbiguous`.
  `EntityAttributesConflicted` was previously the only event type and a
  standalone record.
- **`EntityAttributesConflicted` reshaped** to
  `(Entity stored, Claim claim, List<AttributeDiff> differingValues)`.
  `occurredAt` is dropped (events are fire-and-forget; a sink stamps a
  time if it needs one); `incoming` is renamed `claim`; `differences`
  is renamed `differingValues`.
- **Conflict semantics narrowed.** Only **differing values for shared
  attribute names** raise an `EntityAttributesConflicted`. A claim that
  carries a new, claim-only attribute is now **additive — no longer a
  conflict**.
- **`EntityStorage.findCandidates(Claim)` → `findCandidates(Claim, int
  limit)`.** The capped two-arg form is the primary port method;
  `findCandidates(Claim)` remains as a convenience default delegating to
  `Integer.MAX_VALUE`. Adapter implementors must honour the limit.
- **Starter: `jclaim.conflict-sink.*` → `jclaim.match-sink.*`** with a
  new type enum `noop | logging | spring-events` (was `none | log |
  spring-event`). The bridged event type `JclaimConflictEvent` →
  `JclaimMatchEvent` (now wrapping the sealed `MatchEvent`).
  `LoggingConflictSink` → `LoggingMatchSink`,
  `SpringEventConflictSink` → `SpringEventMatchSink`.
- **`HumanIdGenerator.isValid` / `HumanIdFormat.isValid` are now
  fixed-width** and require the format's literal separators at their exact
  positions. The old `normalise`-based path silently stripped hyphens
  before checking, so loosely-formatted input (missing or misplaced
  separators) was wrongly accepted as valid.

### Changed

- **Restructured from single-module to multi-module Maven project.**
  Repository root now holds the aggregator POM (`packaging=pom`) with
  shared `dependencyManagement` and `pluginManagement`. All current
  source moved into a new `jclaim-core` module. Prepares the project
  for forthcoming storage adapter modules (`jclaim-storage-mongo`,
  `jclaim-storage-postgres`) without changing any existing code.
- **Artifact ID rename: `jclaim` → `jclaim-core`.** Consumers depending
  on the library use the new coordinates:
  ```xml
  <dependency>
      <groupId>uk.codery</groupId>
      <artifactId>jclaim-core</artifactId>
      <version>0.1.0-SNAPSHOT</version>
  </dependency>
  ```
  No Maven Central release has been cut, so no external consumer is
  affected; pre-1.0 in-development users need to update local
  references.
- Version held at `0.1.0-SNAPSHOT` across the structural change. No
  published artefact exists yet, and the public API is unchanged — a
  version bump would imply behaviour evolution that has not happened.
- Examples remain at the repository root (`/examples/`) and are
  pulled onto `jclaim-core`'s test classpath via
  `build-helper-maven-plugin`, preserving the existing test-fixture
  coupling.
- Build workflow's surefire-reports upload path switched to
  `**/target/surefire-reports/` so per-module results are collected
  under the reactor build.

### Added

- Core domain model: `Entity`, `EntityId`, `Claim`, `Alias`, `SourceSystem`, `MatchingAttribute`.
- Sealed `ResolutionResult` (`Matched` / `Minted`).
- `EntityResolver` interface with `resolveOrMint`, `getByUrn`, `findByHumanId`, `findByAlias`, `addAlias`.
- Single concrete service implementation `DefaultEntityResolver`.
- Storage port (`EntityStorage`) with in-memory adapter (`InMemoryEntityStorage`).
- UUID v7 URN generation via `com.github.f4b6a3:uuid-creator`.
- Crockford Base32 encoder with Damm check digit for human-friendly IDs (`K7M2-9X4P-N`).
- `ConflictEventSink` for `EntityAttributesConflicted` events emitted when matched-entity attributes diverge from incoming claim.
- Maven build, JaCoCo line coverage at 80% per package, source + Javadoc jars.
- GitHub Actions: build-and-test workflow; Maven Central publish workflow stub (not yet activated).
- FOSSA licence scanning workflow (`.github/workflows/fossa.yml`) running `fossa analyze` and `fossa test` on push to main and pull requests; shield badge in README top band and large status banner under the License section.
- Codecov coverage badge in README top band; coverage XML produced by every module's JaCoCo report and uploaded by the build workflow.

### Follow-up (manual, post-merge)

- Create the FOSSA project at https://app.fossa.com against
  `thekitchencoder/jclaim`, capture an API key, and add it to the
  GitHub repository's Actions secrets as `FOSSA_API_KEY`. Until the
  secret is set, the FOSSA workflow will fail on push — this is
  expected.
- Verify the Codecov project auto-links and appears at
  https://app.codecov.io/gh/thekitchencoder/jclaim after the first
  build-workflow run on main.
- Re-import the Maven project in your IDE so the new modules
  register.

[Unreleased]: https://github.com/thekitchencoder/jclaim/compare/0.2.0...HEAD
[0.2.0]: https://github.com/thekitchencoder/jclaim/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/thekitchencoder/jclaim/releases/tag/0.1.0
