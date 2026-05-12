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
module: `jclaim-core` holds the port + in-memory adapter + abstract
conformance suite, and the two storage adapters ship as sibling
modules.

```
jclaim/
├── pom.xml                                         # Aggregator: groupId+version, dependencyManagement, pluginManagement
├── examples/                                       # Top-level runnable QuickStart classes
│   ├── RetailQuickStart.java                       # registered on jclaim-core's test classpath
│   ├── ProductQuickStart.java                      # via build-helper-maven-plugin
│   └── PropertyQuickStart.java
├── jclaim-storage-postgres/                        # PostgreSQL adapter — plain JDBC, normalised 3-table schema
├── jclaim-storage-mongo/                           # MongoDB adapter — single-collection, unique compound index
├── jclaim-spring-boot-starter/                     # Spring Boot 3.x auto-configuration; consumes core + storage adapters
└── jclaim-core/
    ├── pom.xml                                     # Inherits parent; declares own deps
    └── src/
        ├── main/java/uk/codery/jclaim/
        │   ├── id/                                 # Identifier generation
        │   │   ├── CrockfordBase32.java            # 32-symbol alphabet, ambiguous chars dropped
        │   │   ├── Damm.java                       # Single-digit checksum, totally anti-symmetric quasigroup
        │   │   ├── HumanIdGenerator.java           # K7M2-9X4P-N style human-friendly IDs
        │   │   └── UuidV7.java                     # RFC 9562 time-ordered UUID
        │   ├── model/                              # Domain model (immutable records)
        │   │   ├── Alias.java                      # (source, sourceId) pair
        │   │   ├── Claim.java                      # Inbound identity claim
        │   │   ├── Entity.java                     # Reconciled canonical entity
        │   │   ├── EntityId.java                   # URN wrapper around UUID v7
        │   │   ├── MatchingAttribute.java          # Typed attribute (name, value)
        │   │   ├── ResolutionResult.java           # sealed: Matched | Minted
        │   │   └── SourceSystem.java               # Named source-system reference
        │   ├── event/                              # Conflict event surface
        │   │   ├── AttributeDiff.java              # Per-attribute divergence record
        │   │   ├── ConflictEventSink.java          # Pluggable consumer (default no-op)
        │   │   └── EntityAttributesConflicted.java # Emitted when matched entity differs from claim
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
- **Modules list** — currently `jclaim-core`; storage adapter modules
  drop in alongside it.

## Core Concepts

### 1. URN scheme

Every reconciled entity carries a URN of the form:

```
urn:<namespace>:entity:<UUID v7>
```

The namespace is caller-configurable. The example shape `urn:codery:entity:<UUID>`
is documented in the README. UUIDs are RFC 9562 version 7 (time-ordered,
B-tree friendly) generated via `com.github.f4b6a3:uuid-creator`.

### 2. Human-friendly IDs

Each entity has a separate `humanId` minted at registration:

- 8 random Crockford Base32 characters (40 bits of entropy)
- 1 Damm check digit
- Display format: `XXXX-XXXX-X` (e.g. `K7M2-9X4P-N`)

Crockford Base32 drops the ambiguous symbols `I`, `L`, `O`, `U` and accepts
case-insensitive input with the swap aliases `i/l → 1`, `o → 0`. The Damm
algorithm catches all single-digit and adjacent-transposition errors. Human IDs
are **not derived** from the URN — they are a separate, independently-minted
lookup attribute. Storage enforces uniqueness; on collision the resolver
re-mints.

### 3. Match-or-mint

The single resolver operation `resolveOrMint(Claim)` returns a
`ResolutionResult`:

- `Matched(entity)` — the (source, sourceId) alias already belongs to a stored
  entity
- `Minted(entity)` — no existing alias; a new entity is created with the claim
  alias attached

The match is **alias-only in v0**. A future session will layer a configurable
matching policy expressed in JSpec on top of this baseline.

### 4. Conflict events

When `resolveOrMint` matches a stored entity but the incoming claim's attributes
differ from the stored attributes, an `EntityAttributesConflicted` event is
delivered to the configured `ConflictEventSink`. Stored attributes are **not**
silently updated — evidence is preserved for stewardship. The default sink is a
no-op; integrators wire it to SLF4J, Spring's `ApplicationEventPublisher`,
Kafka, etc.

### 5. Storage as a port

`EntityStorage` is a small port interface:

- `findByUrn(EntityId)`
- `findByHumanId(String)`
- `findByAlias(Alias)`
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
- **Match** vs **Mint** — the two outcomes of `resolveOrMint`. Match is
  alias-driven in v0.
- **Source system** — a named origin of claims (`ecommerce`, `pos`, `crm`).
  Represented by `SourceSystem(String name)`.
- **Matching attribute** — a typed `(name, value)` pair carried on claims and
  entities. Will be referenced by the future matching policy DSL.
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

Designed for extension; not yet implemented:

- **Spring Boot starter** — **Delivered** as `jclaim-spring-boot-starter`
  — auto-configures the resolver, selects a storage adapter from the
  classpath (in-memory by default, opt-in Mongo / Postgres), bridges
  conflict events to Spring's `ApplicationEventPublisher` as
  `JclaimConflictEvent`, and ships optional Actuator health +
  Micrometer metrics.
- **Matching policy DSL** — separate session. JSpec specifications
  express the matching policy; tri-state evaluation maps to `MATCHED`
  / `NOT_MATCHED` / `UNDETERMINED`.
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
3. **Are conflict events emitted rather than silent updates?**
4. **Is the change Spring-independent in core packages?**
5. **Are there tests for this change?**
6. **Is logging using SLF4J, not `System.out` / `System.err`?**

## Project Status

- **Version**: 0.1.0-SNAPSHOT (held across the multi-module split
  because no Maven Central artefact has been published yet)
- **Java Version**: 21
- **Layout**: Multi-module Maven; four published modules
  (`jclaim-core`, `jclaim-storage-mongo`, `jclaim-storage-postgres`,
  `jclaim-spring-boot-starter`).
- **In scope this milestone**: domain model, resolver, conflict
  events, in-memory storage, MongoDB + PostgreSQL adapters, abstract
  `EntityStorageContract` suite pinning every adapter to identical
  behaviour, corpus reconciliation contracts shared across all three
  backends, build + workflow scaffolding, FOSSA + Codecov CI
  integrations.
- **Also in scope this milestone**: `jclaim-spring-boot-starter` —
  Spring Boot 3.x auto-configuration wiring the resolver, selecting a
  storage adapter from the classpath, bridging conflict events to
  `ApplicationEventPublisher`, plus optional Actuator health +
  Micrometer metrics.
- **Next session**: matching policy DSL via JSpec composition.
