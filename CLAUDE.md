# CLAUDE.md — AI Assistant Context

<!-- brain -->
effort: Efforts/entity-reconciliation-library.md
summary: Generic OSS library for cross-domain entity reconciliation; embeddable, Spring-independent, composes with jspec for matching policy.
<!-- /brain -->

This document provides context for AI assistants (like Claude) working with the
JClaim codebase.

## Project Overview

**JClaim** is an embeddable, Spring-independent Java 21 library for entity
identity reconciliation. Given identity claims about an entity from multiple
source systems, JClaim returns a single stable canonical identifier — matching
to one if the entity is already known, minting one if not.

It is the MDM (Master Data Management) entity-matching pattern packaged as a
library rather than an enterprise platform. Sibling library to
[JSpec](https://github.com/thekitchencoder/jspec) in the `uk.codery` namespace.

### Key Characteristics

- **Language**: Java 21 (records, sealed interfaces, pattern matching, switch
  expressions)
- **Build Tool**: Maven
- **Architecture**: Domain model → resolver service → storage port
- **Design Philosophy**: Immutable records, thread-safe, port-based storage,
  Spring-independent
- **Dependencies**: `uuid-creator` (UUID v7), SLF4J (logging), Lombok (provided)

## Codebase Structure

JClaim is a multi-module Maven project. The repository root holds the
aggregator POM (`packaging=pom`) and each capability lives in its own
module: `jclaim-core` holds the storage + matching ports, the
in-memory adapter, and the abstract conformance suite; the two storage
adapters and the JSPEC matching provider ship as sibling modules.

```
jclaim/
├── pom.xml                                         # Aggregator: groupId+version, dependencyManagement, pluginManagement
├── examples/                                       # Top-level runnable QuickStart classes
│   ├── RetailQuickStart.java                       # registered on jclaim-core's test classpath
│   ├── ProductQuickStart.java                      # via build-helper-maven-plugin
│   └── PropertyQuickStart.java
├── jclaim-matching-jspec/                          # JSPEC-backed MatchingPolicy provider — uk.codery.jclaim.matching.jspec; depends on jclaim-core + uk.codery:jspec
├── jclaim-storage-postgres/                        # PostgreSQL adapter — plain JDBC, normalised 3-table schema
├── jclaim-storage-mongo/                           # MongoDB adapter — single-collection, unique compound index
├── jclaim-spring-boot-starter/                     # Spring Boot 3.x auto-configuration; consumes core + storage adapters + (optional) matching provider
└── jclaim-core/
    ├── pom.xml                                     # Inherits parent; declares own deps
    └── src/
        ├── main/java/uk/codery/jclaim/
        │   ├── id/                                 # Identifier generation
        │   │   ├── CrockfordBase32.java            # 32-symbol alphabet, ambiguous chars dropped
        │   │   ├── Damm.java                       # Single-digit checksum, totally anti-symmetric quasigroup
        │   │   ├── HumanIdGenerator.java           # K7M2-9X4P-3 style human-friendly IDs
        │   │   └── UuidV7.java                     # RFC 9562 time-ordered UUID
        │   ├── model/                              # Domain model (immutable records)
        │   │   ├── Alias.java                      # (source, sourceId) pair
        │   │   ├── Claim.java                      # Inbound identity claim
        │   │   ├── Entity.java                     # Reconciled canonical entity
        │   │   ├── EntityId.java                   # URN wrapper around UUID v7
        │   │   ├── MatchingAttribute.java          # Typed attribute (name, value)
        │   │   ├── ResolutionResult.java           # sealed: Matched | Minted
        │   │   └── SourceSystem.java               # Named source-system reference
        │   ├── matching/                           # Matching policy port (no jspec dependency)
        │   │   ├── MatchingPolicy.java             # Port: TriState evaluate(Claim, Entity); aliasOnly() default
        │   │   ├── TriState.java                   # MATCHED | NOT_MATCHED | UNDETERMINED
        │   │   └── AliasOnlyMatchingPolicy.java    # Stateless singleton default — alias membership only
        │   ├── event/                              # Stewardship event surface
        │   │   ├── AttributeDiff.java              # Per-attribute divergence record
        │   │   ├── CandidateOutcome.java           # (candidate, TriState) pair carried on deferred-resolution events
        │   │   ├── MatchEvent.java                 # sealed: EntityAttributesConflicted | MatchUndecided | MatchAmbiguous
        │   │   ├── MatchEventSink.java             # Pluggable consumer (default no-op)
        │   │   ├── EntityAttributesConflicted.java # (Entity stored, Claim claim, List<AttributeDiff> differingValues)
        │   │   ├── MatchUndecided.java             # Mint left at least one candidate UNDETERMINED
        │   │   └── MatchAmbiguous.java             # Multiple candidates MATCHED; winner = oldest, tiebreak urn
        │   ├── storage/                            # Storage port + in-memory adapter
        │   │   ├── EntityStorage.java              # Port interface
        │   │   ├── StorageOutcome.java             # sealed: Existing | Created
        │   │   └── memory/
        │   │       └── InMemoryEntityStorage.java  # ConcurrentHashMap-backed adapter
        │   └── resolver/                           # Application service
        │       ├── DefaultEntityResolver.java      # Concrete implementation
        │       └── EntityResolver.java             # Public interface
        └── test/java/uk/codery/jclaim/
            ├── id/                                 # Crockford, Damm, HumanId tests
            ├── storage/                            # EntityStorageContract — abstract suite every adapter passes
            │   └── memory/                         # In-memory adapter pinned to the contract
            ├── retail/, product/, property/        # Abstract corpus reconciliation tests + in-memory bindings
            ├── resolver/                           # Resolver happy-path + conflict tests
            └── examples/                           # Tests that pin the QuickStart classes to the API
```

The `jclaim-core` test sources publish as a `tests`-classifier jar so
adapter modules consume the conformance suite + corpus contracts via
`<type>test-jar</type><scope>test</scope>`.

The aggregator centralises:

- **Dependency versions** in `<dependencyManagement>` — modules declare
  GAV without `<version>`.
- **Plugin versions and shared configuration** in `<pluginManagement>` —
  modules activate plugins by GA; the parent supplies version and
  execution config (compiler release level, JaCoCo coverage rules,
  Surefire defaults, source/javadoc jar generation, GPG signing under
  the `release` profile).
- **Modules list** — `jclaim-core`, `jclaim-matching-jspec`, the two
  storage adapters, and `jclaim-spring-boot-starter`. The
  `jspec.version` (0.6.0) is managed centrally but added as a real
  dependency only by `jclaim-matching-jspec` — never by `jclaim-core`.

## Core Concepts

### 1. URN scheme

Every reconciled entity carries a URN of the form:

```
urn:<namespace>:<type>:<UUID v7>
```

Both the namespace and the `type` segment are caller-configurable per
resolver. The `type` segment defaults to `entity`
(`EntityId.DEFAULT_TYPE`) and is set via
`DefaultEntityResolver.Builder.entityType(...)` or the Spring property
`jclaim.urn.type`; the namespace is set via the builder or
`jclaim.urn.namespace` (renamed from the former `jclaim.namespace`,
default `codery`). `EntityId.of(namespace, type, uuid)` constructs a
URN; the two-arg `EntityId.of(namespace, uuid)` defaults `type` to
`entity`. The example shape `urn:codery:entity:<UUID>` is the default.
UUIDs are RFC 9562 version 7 (time-ordered, B-tree friendly) generated
via `com.github.f4b6a3:uuid-creator`.

Conceptually the namespace is the **tenant/organisation** (one shared value
per application) and the `type` is the **entity type** a resolver reconciles —
one resolver reconciles exactly one entity type. An application today
configures a single (default) entity type via these top-level keys;
reconciling multiple entity types in one application — a
`jclaim.entity-types.<type>` map inheriting these as defaults — is a planned,
additive extension (see
`docs/plans/2026-06-04-multi-entity-type-direction.md`).

### 2. Human-friendly IDs

The `humanId` is **opt-in, driven by the presence of a template**.
`Entity.humanId` is **nullable**: with no template configured (the
default) the resolver mints entities with `humanId == null` — no
generation, no stored field, no index entry. Configure a template to opt
in, and each entity then carries a separate `humanId` minted at
registration:

- random Crockford Base32 data characters (8 → 40 bits of entropy with
  the historic template)
- 1 Damm check digit
- Display format follows the template, e.g. `XXXX-XXXX-X` (`K7M2-9X4P-3`)

The template is supplied via `HumanIdFormat.ofTemplate(...)`, the resolver
builder slot `DefaultEntityResolver.Builder.humanIdTemplate(String)`
(null/blank → no humanId; the former `humanIdFormat(HumanIdFormat)` setter
was **dropped**), or the Spring property `jclaim.human-id.template`
(default **none**; eagerly validated — a malformed *non-blank* template
fails context startup). Grammar: `?` is a placeholder (the **last** `?`
renders the Damm check digit, every other `?` a random data symbol) and
any other character is a literal emitted verbatim; 1–12 data placeholders
(2–13 `?` total) keep the value within the ≤60-bit `long` ceiling. The
template `????-????-?` reproduces the historic `XXXX-XXXX-X` shape.

Crockford Base32 drops the ambiguous symbols `I`, `L`, `O`, `U` and accepts
case-insensitive input with the swap aliases `i/l → 1`, `o → 0`. The Damm
algorithm catches all single-digit and adjacent-transposition errors. Human IDs
are **not derived** from the URN — they are a separate, independently-minted
lookup attribute. Storage enforces uniqueness via a **partial** unique
index covering only entities that have a humanId (Postgres
`... WHERE human_id IS NOT NULL`, Mongo a `$exists` partial filter);
on collision the resolver re-mints.

### 3. Match-or-mint

The single resolver operation `resolveOrMint(Claim)` returns a
`ResolutionResult`:

- `Matched(entity)` — the (source, sourceId) alias already belongs to a stored
  entity
- `Minted(entity)` — no existing alias; a new entity is created with the claim
  alias attached

An exact `(source, sourceId)` alias owner short-circuits straight to
`Matched` (preserving alias atomicity). When no exact owner exists, the
resolver blocks a candidate pool (`findCandidates`, capped by
`maxCandidates`, default 100) and scores each candidate with the
configured `MatchingPolicy`, which returns a `TriState`. Exactly one
`MATCHED` → `Matched` (alias linked); several `MATCHED` → `Matched`
(oldest by `createdAt`, tiebreak urn) plus a `MatchAmbiguous` event; no
`MATCHED` → `Minted`, plus a `MatchUndecided` event if any candidate was
`UNDETERMINED`. The resolver always returns an identity; ambiguity
surfaces as stewardship events. The **default policy is
`MatchingPolicy.aliasOnly()`** — behaviour identical to the historic
alias-only baseline.

### 4. Stewardship events

Stewardship events are delivered to the configured `MatchEventSink` (default
no-op; integrators wire it to SLF4J, Spring's `ApplicationEventPublisher`,
Kafka, etc.). The sealed `MatchEvent` permits three variants:

- `EntityAttributesConflicted(Entity stored, Claim claim, List<AttributeDiff>
  differingValues)` — a matched entity's stored attributes disagree with the
  claim. Only **differing values for shared attribute names** count; a
  claim-only (new) attribute is **additive, not a conflict**. (`occurredAt` was
  dropped — events are fire-and-forget; a sink stamps a time if it needs one.)
- `MatchUndecided` — a fresh mint had only `UNDETERMINED` candidates.
- `MatchAmbiguous` — multiple candidates `MATCHED`; the winner (oldest by
  `createdAt`, tiebreak urn) is linked and the alternatives surfaced.

Events carry `TriState` outcomes only — never jspec types, so core stays
jspec-free. Stored attributes are **not** silently updated; evidence is
preserved for stewardship.

### 5. Storage as a port

`EntityStorage` is a small port interface:

- `findByUrn(EntityId)`
- `findByHumanId(String)`
- `findByAlias(Alias)`
- `findCandidates(Claim, int limit)` — capped candidate pool for the
  matching policy (one-arg overload delegates to `Integer.MAX_VALUE`)
- `resolveOrCreate(Alias, Supplier<Entity>) -> StorageOutcome` — atomic against
  the alias index
- `addAlias(EntityId, Alias) -> Entity` — atomic on the alias index

`InMemoryEntityStorage` ships in `jclaim-core` for tests and
evaluation. Production adapters:

- **`jclaim-storage-mongo`** — single collection, unique compound
  index on `(aliases.source, aliases.sourceId)`. `resolveOrCreate`
  uses optimistic alias lookup + `insertOne` guarded by the index;
  `addAlias` uses `$addToSet`. Indexes auto-created on construction.
- **`jclaim-storage-postgres`** — plain-JDBC adapter against
  `entities`, `entity_aliases`, `entity_attributes` (normalised
  3-table schema). `resolveOrCreate` uses transactional INSERT
  guarded by the primary key on `(source, source_id)`; `addAlias`
  uses single-row INSERT. Schema auto-applied from a classpath
  `schema.sql` on construction.

Every adapter — including the in-memory reference — passes the
abstract `EntityStorageContract` suite in `jclaim-core`'s test-jar.
Adding a new adapter is: implement `EntityStorage`, extend
`EntityStorageContract` with a `newStorage()` factory, and pass.

## Terminology & Naming

- **Entity** — the canonical reconciled record. Never "Person", "Customer",
  "Vehicle" in core code; specialisations live in caller code.
- **Claim** — an inbound identity assertion from a source system: `(source,
  sourceId, attributes)`.
- **Alias** — the `(source, sourceId)` pair after it has been linked to a
  canonical entity.
- **Match** vs **Mint** — the two outcomes of `resolveOrMint`. An exact alias
  owner matches directly; otherwise the `MatchingPolicy` scores a candidate
  pool to a `TriState` and the resolver matches or mints accordingly.
- **Source system** — a named origin of claims (`ecommerce`, `pos`, `crm`).
  Represented by `SourceSystem(String name)`.
- **Matching attribute** — a typed `(name, value)` pair carried on claims and
  entities. Projected into the target/context documents the JSPEC matching
  policy queries.
- **TriState** — the matching-policy verdict for one candidate: `MATCHED` /
  `NOT_MATCHED` / `UNDETERMINED`. Lives in `jclaim-core`; carries no jspec
  coupling.
- **humanId** — the Crockford+Damm display ID. Lower case to avoid clashing
  with a hypothetical `HumanId` value type if one is added later.

## Development Guidelines

### Making Code Changes

1. **Preserve immutability** — all records must remain immutable. Defensively
   copy collection inputs in compact constructors.
2. **Maintain thread safety** — no mutable shared state. Storage adapters must
   enforce alias uniqueness atomically.
3. **No Spring imports in core** — `uk.codery.jclaim.*` must not depend on
   `org.springframework.*`.
4. **Add tests** — every new operation needs unit tests covering the happy path
   and the relevant failure mode.
5. **Log, don't print** — SLF4J, not `System.out` / `System.err`.

### Adding a New Storage Adapter

Storage adapters live as their own Maven modules so consumers can pull
in only the storage technology they need. To add a new adapter (e.g.
DynamoDB):

1. Create a new module directory at the repository root, e.g.
   `jclaim-storage-dynamo/`, and register it in the aggregator POM's
   `<modules>` list.
2. The module's `pom.xml` inherits from the parent and adds a
   compile-scope dependency on `jclaim-core` (so the port interface
   and domain records are available).
3. Place the implementation under
   `uk.codery.jclaim.storage.<adapter>` to match the package
   convention.
4. Implement `EntityStorage`. The atomic contract on `resolveOrCreate`
   and `addAlias` is the part adapter authors must focus on — naïve
   read-then-write implementations are wrong.
5. Express alias uniqueness as a database-enforced constraint where
   possible.
6. Mirror the test contract used by `InMemoryEntityStorageTest`.

### Logging Levels

- **WARN** — alias collisions during mint, humanId collisions during mint
- **INFO** — entity minted / matched (optional, off by default in adapters)
- **DEBUG** — per-claim resolution decisions
- **TRACE** — attribute-level diff details

## Common Tasks

All Maven commands run from the repository root and walk the reactor
unless `-pl` selects a single module.

### Running Tests

```bash
mvn test                                          # Every test across every module
mvn -pl jclaim-core test                          # Single-module run
mvn -pl jclaim-core test -Dtest=DefaultEntityResolverTest
```

### Building

```bash
mvn clean install                                 # Full reactor build with tests
mvn clean package -DskipTests                     # Build artefacts without tests
```

### Coverage

```bash
mvn test jacoco:report                            # Per-module reports under <module>/target/site/jacoco
```

### Running the QuickStart examples

```bash
mvn -q -pl jclaim-core test-compile exec:java \
    -Dexec.mainClass=uk.codery.jclaim.examples.RetailQuickStart \
    -Dexec.classpathScope=test
```

Substitute `ProductQuickStart` or `PropertyQuickStart` to demonstrate
the other corpora. The examples live at `/examples/` and are pulled
onto `jclaim-core`'s test classpath via
`build-helper-maven-plugin`.

## Extension Points

Designed-in extension points; delivery status is noted per item:

- **Spring Boot starter** — **Delivered** as `jclaim-spring-boot-starter`
  — auto-configures the resolver, selects a storage adapter from the
  classpath (in-memory by default, opt-in Mongo / Postgres), bridges
  stewardship events to Spring's `ApplicationEventPublisher` as
  `JclaimMatchEvent`, and ships optional Actuator health + Micrometer
  metrics. Matching is configured via `jclaim.matching.spec` (classpath
  spec; eager-validated, requires `jclaim-matching-jspec`) and
  `jclaim.matching.max-candidates`; sink wiring lives under
  `jclaim.match-sink.*` (`noop|logging|spring-events`).
- **Matching policy DSL** — **Delivered**. The `MatchingPolicy` port +
  `aliasOnly()` default live in `jclaim-core`; the JSPEC-backed
  implementation ships in `jclaim-matching-jspec`
  (`JspecMatchingPolicy.fromResource(...)` / `.fromString(...)` /
  builder), depending on `uk.codery:jspec:0.6.0`. Each `(Claim,
  candidate)` pair projects to target/context documents; spec operands
  late-bind candidate values via the `$contextPath` sentinel; the
  `EvaluationOutcome` collapses to a `TriState` via the (default
  conjunctive) `OutcomeAggregator`. Tri-state maps to `MATCHED` /
  `NOT_MATCHED` / `UNDETERMINED`.
- **Additional storage adapters** — third-party adapters depend on
  `jclaim-core` (compile) and `jclaim-core` tests-classifier (test),
  implement `EntityStorage`, and extend `EntityStorageContract` to
  prove conformance. DynamoDB, Cassandra, etc. drop in this way.
- **Merge / split operations** — deferred per the design effort note.

## Design References

- Effort note (private vault) — `Efforts/entity-reconciliation-library.md`
- README opening stanza draft — `Efforts/entity-reconciliation-library/readme-draft.md`
- Sibling project conventions — [JSpec](https://github.com/thekitchencoder/jspec)

## Questions for AI Assistants

When working with this codebase, consider:

1. **Is this change preserving immutability?**
2. **Does this maintain alias-uniqueness atomicity?**
3. **Are stewardship events emitted rather than silent updates?**
4. **Is the change Spring-independent in core packages?**
5. **Are there tests for this change?**
6. **Is logging using SLF4J, not `System.out` / `System.err`?**

## Project Status

- **Version**: 0.1.0-SNAPSHOT (held across the multi-module split
  because no Maven Central artefact has been published yet)
- **Java Version**: 21
- **Layout**: Multi-module Maven; five published modules
  (`jclaim-core`, `jclaim-matching-jspec`, `jclaim-storage-mongo`,
  `jclaim-storage-postgres`, `jclaim-spring-boot-starter`).
- **Delivered — foundational milestone**: domain model, resolver, stewardship
  events, in-memory storage, MongoDB + PostgreSQL adapters, abstract
  `EntityStorageContract` suite pinning every adapter to identical
  behaviour, corpus reconciliation contracts shared across all three
  backends, build + workflow scaffolding, FOSSA + Codecov CI
  integrations.
- **Also delivered**: `jclaim-spring-boot-starter` —
  Spring Boot 3.x auto-configuration wiring the resolver, selecting a
  storage adapter from the classpath, bridging stewardship events to
  `ApplicationEventPublisher`, plus optional Actuator health +
  Micrometer metrics.
- **Matching policy DSL — delivered**: `MatchingPolicy` port +
  `aliasOnly()` default in `jclaim-core`; JSPEC-backed
  `JspecMatchingPolicy` in `jclaim-matching-jspec` (jspec 0.6.0,
  `$contextPath` operands); capped candidate pool
  (`findCandidates(Claim, int)`, `maxCandidates`); sealed `MatchEvent`
  (`EntityAttributesConflicted` / `MatchUndecided` / `MatchAmbiguous`)
  on the renamed `MatchEventSink`; starter `jclaim.matching.*`
  auto-configuration.
- **Next session**: merge / split operations.
