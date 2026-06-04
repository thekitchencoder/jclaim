# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
  a 60-bit `long`. Resolver builder slots
  `DefaultEntityResolver.Builder.humanIdTemplate(String)` /
  `.humanIdFormat(HumanIdFormat)`; starter property
  `jclaim.human-id.template` (default `????-????-?`), eagerly validated —
  a malformed template fails context startup. The default template
  reproduces the historic `XXXX-XXXX-X` shape exactly.
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

[Unreleased]: https://github.com/thekitchencoder/jclaim/commits/main
