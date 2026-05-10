# CLAUDE.md — AI Assistant Context

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

```
jclaim/
├── pom.xml
├── src/main/java/uk/codery/jclaim/
│   ├── id/                                         # Identifier generation
│   │   ├── CrockfordBase32.java                    # 32-symbol alphabet, ambiguous chars dropped
│   │   ├── Damm.java                               # Single-digit checksum, totally anti-symmetric quasigroup
│   │   ├── HumanIdGenerator.java                   # K7M2-9X4P-N style human-friendly IDs
│   │   └── UuidV7.java                             # RFC 9562 time-ordered UUID
│   ├── model/                                      # Domain model (immutable records)
│   │   ├── Alias.java                              # (source, sourceId) pair
│   │   ├── Claim.java                              # Inbound identity claim
│   │   ├── Entity.java                             # Reconciled canonical entity
│   │   ├── EntityId.java                           # URN wrapper around UUID v7
│   │   ├── MatchingAttribute.java                  # Typed attribute (name, value)
│   │   ├── ResolutionResult.java                   # sealed: Matched | Minted
│   │   └── SourceSystem.java                       # Named source-system reference
│   ├── event/                                      # Conflict event surface
│   │   ├── AttributeDiff.java                      # Per-attribute divergence record
│   │   ├── ConflictEventSink.java                  # Pluggable consumer (default no-op)
│   │   └── EntityAttributesConflicted.java         # Emitted when matched entity differs from claim
│   ├── storage/                                    # Storage port + adapters
│   │   ├── EntityStorage.java                      # Port interface
│   │   ├── StorageOutcome.java                     # sealed: Existing | Created
│   │   └── memory/
│   │       └── InMemoryEntityStorage.java          # ConcurrentHashMap-backed adapter
│   └── resolver/                                   # Application service
│       ├── DefaultEntityResolver.java              # Concrete implementation
│       └── EntityResolver.java                     # Public interface
└── src/test/java/uk/codery/jclaim/
    ├── id/                                         # Crockford, Damm, HumanId tests
    ├── storage/memory/                             # In-memory adapter tests
    └── resolver/                                   # Resolver happy-path + conflict tests
```

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

`InMemoryEntityStorage` ships in this module. A `MongoEntityStorage` follows in
a separate session. The port surface is deliberately small and Mongo-friendly:
the atomic `resolveOrCreate` primitive maps to `findOneAndUpdate(..., upsert)`
guarded by a unique compound index on `(source, sourceId)`.

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

To add a new adapter (e.g. Postgres, DynamoDB):

1. Create a new package under `uk.codery.jclaim.storage.<adapter>`.
2. Implement `EntityStorage`. The atomic contract on `resolveOrCreate` and
   `addAlias` is the part adapter authors must focus on — naïve
   read-then-write implementations are wrong.
3. Express alias uniqueness as a database-enforced constraint where possible.
4. Mirror the test contract used by `InMemoryEntityStorageTest`.

### Logging Levels

- **WARN** — alias collisions during mint, humanId collisions during mint
- **INFO** — entity minted / matched (optional, off by default in adapters)
- **DEBUG** — per-claim resolution decisions
- **TRACE** — attribute-level diff details

## Common Tasks

### Running Tests

```bash
mvn test                                # Run all tests
mvn test -Dtest=DefaultEntityResolverTest
```

### Building

```bash
mvn clean install                       # Full build with tests
mvn clean package -DskipTests           # Build without tests
```

### Coverage

```bash
mvn test jacoco:report                  # HTML report under target/site/jacoco
```

## Extension Points

Designed for extension; not yet implemented in this module:

- **MongoEntityStorage** — next session. Same port, document-shape mapping,
  unique compound index on `(source, sourceId)`.
- **Matching policy DSL** — separate session. JSpec specifications express the
  matching policy; tri-state evaluation maps to `MATCHED` / `NOT_MATCHED` /
  `UNDETERMINED`.
- **Merge / split operations** — deferred per the design effort note.
- **Retail synthetic dataset** — separate session, supplies integration test
  corpus.

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

- **Version**: 0.1.0-SNAPSHOT
- **Java Version**: 21
- **In scope this milestone**: domain model, resolver, in-memory storage, human ID
  generation, conflict events, build + workflow scaffolding.
- **Next session**: Mongo storage adapter.
- **After that**: matching policy DSL via JSpec composition.
