# JCLAIM

[![Maven Central](https://img.shields.io/maven-central/v/uk.codery/jclaim-core.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22uk.codery%22%20AND%20a:%22jclaim-core%22)
[![Build and Test](https://github.com/thekitchencoder/jclaim/actions/workflows/build.yml/badge.svg)](https://github.com/thekitchencoder/jclaim/actions/workflows/build.yml)
[![codecov](https://codecov.io/gh/thekitchencoder/jclaim/branch/main/graph/badge.svg)](https://codecov.io/gh/thekitchencoder/jclaim)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![FOSSA Status](https://app.fossa.com/api/projects/git%2Bgithub.com%2Fthekitchencoder%2Fjclaim.svg?type=shield)](https://app.fossa.com/projects/git%2Bgithub.com%2Fthekitchencoder%2Fjclaim?ref=badge_shield)
[![Java](https://img.shields.io/badge/Java-21+-blue.svg)](https://openjdk.org/projects/jdk/21/)

JCLAIM is an embeddable, Spring-independent Java library for **entity identity reconciliation**. Given identity claims about an entity from multiple source systems, JCLAIM returns a single stable canonical identifier — matching to one if the entity is already known, minting one if not.

The MDM (Master Data Management) entity-matching pattern, packaged as a library rather than an enterprise platform. Generic across entity types: configure one instance for Person, another for Vehicle, another for Customer — each with its own matching policy expressed in [JSPEC](https://github.com/thekitchencoder/jspec).

## Features

- **Canonical identity** — One stable URN per entity, minted as UUID v7. Source-system IDs become aliases on the canonical entity.
- **Human-friendly IDs** — Crockford Base32 with Damm check digit (`K7M2-9X4P-N`). Phone-readable, OCR-friendly, transcription-error-resistant.
- **Match-or-mint as one operation** — `resolveOrMint(claim)` returns a `Matched` or `Minted` result. Callers know which path was taken.
- **Matching policy as data** — Express your matching logic as a [JSPEC](https://github.com/thekitchencoder/jspec) specification via the optional `jclaim-matching-jspec` module. Tri-state evaluation surfaces `MATCHED`, `NOT_MATCHED`, and `UNDETERMINED` candidates naturally; the default policy is alias-only, so behaviour is unchanged until you opt in. See [Matching policy](#matching-policy).
- **Alias graph from day one** — Records the mapping from canonical identity to source IDs, with the data shape ready for merge, split, and federation correlation.
- **Stewardship events** — When a match succeeds but stored attributes differ from the new claim, when a mint leaves candidates undetermined, or when several candidates match at once, JCLAIM emits a typed `MatchEvent` rather than silently updating or guessing. Evidence is preserved for stewardship; stored attributes are never overwritten.
- **Storage adapters** — In-memory in `jclaim-core` for tests and evaluation; production adapters for MongoDB (`jclaim-storage-mongo`) and PostgreSQL (`jclaim-storage-postgres`) ship as separate modules. All three back the same conformance suite, so behaviour is identical across paradigms.
- **Spring-independent core, optional Boot integration** — `jclaim-core` and the storage adapters never import Spring. `jclaim-spring-boot-starter` provides idiomatic auto-configuration for Boot users without compromising that independence.
- **Java 21 foundation** — Records, sealed interfaces, switch expressions, immutable collections throughout.

## Installation

```xml
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jclaim-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

`jclaim-core` ships the domain model, the resolver service, the in-memory storage adapter, and the conflict event surface — everything needed to exercise the library end-to-end. Pair it with one of the storage adapter modules for durable persistence:

```xml
<!-- MongoDB adapter -->
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jclaim-storage-mongo</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- PostgreSQL adapter -->
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jclaim-storage-postgres</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- Spring Boot 3.x auto-configuration -->
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jclaim-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- JSPEC-backed matching policy (optional) -->
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jclaim-matching-jspec</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Spring Boot 3.x apps can use `jclaim-spring-boot-starter` for auto-configured wiring (storage adapter selection, conflict-event bridging, Actuator health, Micrometer metrics). See its [module README](./jclaim-spring-boot-starter/README.md). Non-Spring callers use `jclaim-core` directly.

> JCLAIM is in pre-1.0 development; Maven Central publication will follow with the first tagged release.

## Quick Start

The in-memory adapter ships with the core module so the library can be exercised end-to-end without any infrastructure:

```java
import uk.codery.jclaim.model.*;
import uk.codery.jclaim.resolver.*;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import java.util.List;

// 1. Build a resolver against the in-memory storage adapter.
var resolver = DefaultEntityResolver.builder(new InMemoryEntityStorage())
        .namespace("codery")            // urn:codery:entity:<UUID v7>
        .build();

// 2. First claim — the resolver mints a fresh canonical entity.
var firstClaim = new Claim(
        SourceSystem.of("ecommerce"),
        "cust-001",
        List.of(MatchingAttribute.of("email", "alice@example.com")));
var first = resolver.resolveOrMint(firstClaim);

assert first instanceof ResolutionResult.Minted;
System.out.println("urn      = " + first.entity().id().urn());
System.out.println("humanId  = " + first.entity().humanId());

// 3. Second claim, same source alias — the resolver matches.
var second = resolver.resolveOrMint(firstClaim);
assert second instanceof ResolutionResult.Matched;
assert second.entity().equals(first.entity());

// 4. Attach an alias from a second source system.
resolver.addAlias(first.entity().id(), SourceSystem.of("pos"), "loyalty-42");

// 5. Look up by any known alias.
var found = resolver.findByAlias(SourceSystem.of("pos"), "loyalty-42");
assert found.isPresent();
assert found.get().equals(first.entity());
```

### Reacting to stewardship events

The resolver delivers every stewardship event to a `MatchEventSink`. Wire one to observe attribute conflicts, undecided mints, and ambiguous matches:

```java
import uk.codery.jclaim.event.EntityAttributesConflicted;

var resolver = DefaultEntityResolver.builder(new InMemoryEntityStorage())
        .matchEventSink(event -> {
            if (event instanceof EntityAttributesConflicted c) {
                log.warn("conflict on {}: {}", c.stored().id(), c.differingValues());
            }
        })
        .build();
```

The sealed `MatchEvent` hierarchy is `EntityAttributesConflicted`, `MatchUndecided`, and `MatchAmbiguous`. Only **differing values for shared attribute names** raise a conflict — a claim that merely adds a new attribute is additive, not a conflict. The stored entity is **not** updated; silent overwrites are explicitly avoided. Stewardship logic decides whether to overwrite, merge, branch, or escalate.

### Runnable example: retail customer reconciliation

A complete, runnable demonstration lives in
[`examples/RetailQuickStart.java`](examples/RetailQuickStart.java). It
loads five curated customers from the retail synthetic dataset under
[`jclaim-core/src/test/resources/retail-fixtures/`](jclaim-core/src/test/resources/retail-fixtures/),
folds each customer's source-system records into the resolver one alias
at a time, and prints the resulting entity graph:

```
JClaim -- Retail customer reconciliation
========================================

cust-001 -- 4 source record(s)
  resolveOrMint ecommerce/ec-12345           -> Minted   urn:codery:entity:019e17f8-...
  addAlias      pos/pos-78910                -> attached
  addAlias      loyalty/L-22334              -> attached
  addAlias      crm/crm-99887                -> attached

cust-002 -- 3 source record(s)
  resolveOrMint ecommerce/ec-10002           -> Minted   urn:codery:entity:019e17f8-...
  addAlias      loyalty/L-22002              -> attached
  addAlias      crm/crm-30002                -> attached

...

---- Final entity graph ----

cust-001
  urn      = urn:codery:entity:019e17f8-...
  humanId  = BH1Q-90FQ-8
  aliases  :
      ecommerce/ec-12345
      pos/pos-78910
      loyalty/L-22334
      crm/crm-99887
  attributes (from first claim ingested):
      email = jane.doe@example.com
      first_name = Jane
      last_name = Doe
      phone = +44 7700 900123
      registered_at = 2024-03-15
```

Run from the project root:

```bash
mvn -q -pl jclaim-core test-compile exec:java \
    -Dexec.mainClass=uk.codery.jclaim.examples.RetailQuickStart \
    -Dexec.classpathScope=test
```

The retail dataset itself covers around 100 synthetic customers across
four source systems and is documented in
[`jclaim-core/src/test/resources/retail-fixtures/README.md`](jclaim-core/src/test/resources/retail-fixtures/README.md).

### Other corpora

JClaim is entity-agnostic by design, so the test suite exercises the
same library against three different domains:

| Corpus      | Fixtures                                                                 | Example                                           |
|-------------|--------------------------------------------------------------------------|---------------------------------------------------|
| Customers   | [`retail-fixtures/`](jclaim-core/src/test/resources/retail-fixtures/)                | [`RetailQuickStart`](examples/RetailQuickStart.java)   |
| Products    | [`product-fixtures/`](jclaim-core/src/test/resources/product-fixtures/)              | [`ProductQuickStart`](examples/ProductQuickStart.java) |
| Properties  | [`property-fixtures/`](jclaim-core/src/test/resources/property-fixtures/)            | [`PropertyQuickStart`](examples/PropertyQuickStart.java) |

Each corpus is ~100 ground-truth entities across four source systems
with the same scenario shape (single-source, multi-source, conflict
events, similar-looking-but-distinct entities). The library and the
test scaffolding are shared between them — only the YAML data and a
thin domain-named loader wrapper differ.

## Matching policy

`resolveOrMint` first looks for the exact `(source, sourceId)` alias owner. When none exists, instead of minting blindly it blocks a pool of candidates that share an attribute with the claim and scores each with a **matching policy**. The policy returns a `TriState` — `MATCHED`, `NOT_MATCHED`, or `UNDETERMINED` — and the resolver always returns an identity: a single match links the alias, multiple matches link the oldest and emit `MatchAmbiguous`, no match mints (emitting `MatchUndecided` if any candidate was undetermined).

The default policy is `MatchingPolicy.aliasOnly()` — a candidate matches iff it already owns the claim's alias. With the default in place, behaviour is **identical to alias-only matching**; nothing changes until you supply a policy.

Richer policies are expressed as [JSPEC](https://github.com/thekitchencoder/jspec) specifications and supplied by the optional `jclaim-matching-jspec` module. The provider projects each `(claim, candidate)` pair into a target document (`claim.*`) and a context document (`candidate.*`); spec operands late-bind candidate values through the `$contextPath` sentinel:

```yaml
id: customer-match
criteria:
  - id: same-email
    query:
      claim.email:
        $eq: { $contextPath: candidate.email }
  - id: same-postcode
    query:
      claim.postcode:
        $eq: { $contextPath: candidate.postcode }
```

Wire it into the resolver:

```java
import uk.codery.jclaim.matching.jspec.JspecMatchingPolicy;

var resolver = DefaultEntityResolver.builder(new InMemoryEntityStorage())
        .namespace("codery")
        .matchingPolicy(JspecMatchingPolicy.fromResource("/matching/customer-match.yaml"))
        .maxCandidates(100)   // cap the candidate pool (default 100)
        .build();
```

The default aggregation is conjunctive — all criteria `MATCHED` yields `MATCHED`, any `NOT_MATCHED` yields `NOT_MATCHED`, otherwise `UNDETERMINED`. The candidate pool is capped (`maxCandidates`, default 100); truncation is logged at WARN. See the [`jclaim-matching-jspec` module README](./jclaim-matching-jspec/README.md) for projection and aggregator customisation.

## Design

- **URN scheme** — `urn:<namespace>:entity:<UUID v7>`. The namespace is caller-configurable; the UUID is RFC 9562 v7 (time-ordered, B-tree-friendly) generated via [`uuid-creator`](https://github.com/f4b6a3/uuid-creator).
- **Human ID** — Eight Crockford Base32 characters plus a Damm check digit, displayed as `XXXX-XXXX-X`. Independently minted, never derived from the URN. Storage enforces uniqueness; the resolver re-rolls on collision.
- **Storage as a port** — `EntityStorage` exposes five operations: three reads, one atomic `resolveOrCreate` primitive (Mongo-shaped, maps to `findOneAndUpdate` upsert), and one atomic `addAlias`. The MongoDB and PostgreSQL adapters fit this port without any service-code change, and an abstract `EntityStorageContract` suite pins every adapter to identical behaviour across paradigms.
- **Pluggable matching policy** — an exact `(source, sourceId)` alias owner short-circuits to `Matched`, preserving the alias-atomic concurrency guarantee. Otherwise `resolveOrMint` scores a capped candidate pool with the configured `MatchingPolicy` (port in `jclaim-core`, default `aliasOnly()`). Attribute-based matching is driven by a [JSPEC](https://github.com/thekitchencoder/jspec) specification through the optional `jclaim-matching-jspec` provider — see [Matching policy](#matching-policy).

## Documentation

- [`CLAUDE.md`](CLAUDE.md) — AI assistant context, module layout, key invariants.
- [`AGENTS.md`](AGENTS.md) — repository conventions for collaborators and agents.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — contribution workflow, coding standards, test guidelines.
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — Contributor Covenant.
- [`SECURITY.md`](SECURITY.md) — how to report a vulnerability.
- [`CHANGELOG.md`](CHANGELOG.md) — release history.

## Development

```bash
mvn clean verify            # build, test, coverage check
mvn test                    # run JUnit suite
mvn test -Dtest=ClassName   # focused single-class run
```

Requires Java 21 and Maven 3.6+. Lombok annotation processing must be enabled in the IDE.

## Modules

JCLAIM is a multi-module Maven project. The repository root holds the aggregator POM; each capability is a separate Maven module under it.

| Module                        | Purpose                                                                          | Status      |
|-------------------------------|----------------------------------------------------------------------------------|-------------|
| `jclaim-core`                 | Domain model, resolver service, in-memory storage adapter, `MatchingPolicy` port + alias-only default, stewardship events | available   |
| `jclaim-matching-jspec`       | JSPEC-backed `MatchingPolicy` provider — `$contextPath` specs scoring `(claim, candidate)` pairs to a `TriState` — see [module README](jclaim-matching-jspec/README.md) | available |
| `jclaim-storage-mongo`        | MongoDB storage adapter for the `EntityStorage` port — see [module README](jclaim-storage-mongo/README.md) | available |
| `jclaim-storage-postgres`     | PostgreSQL storage adapter for the `EntityStorage` port — see [module README](jclaim-storage-postgres/README.md) | available |
| `jclaim-spring-boot-starter`  | Spring Boot 3.x auto-configuration — wires the resolver, selects a storage adapter, bridges conflict events, adds Actuator health + Micrometer metrics — see [module README](jclaim-spring-boot-starter/README.md) | available |

Consumers depend only on the modules they need. The in-memory adapter is shipped in `jclaim-core` for tests and evaluation; production deployments pair `jclaim-core` with one of the dedicated storage adapter modules. Every adapter passes the same `EntityStorageContract` test suite, so swapping backends is behaviourally transparent.

## Suite

JCLAIM is one of a family of small Java libraries developed under the `uk.codery` namespace:

- [JSPEC](https://github.com/thekitchencoder/jspec) — declarative criteria evaluation against JSON / YAML documents. JCLAIM composes it for matching policy via `jclaim-matching-jspec`.
- JCLAIM — entity identity reconciliation (this project).

## License

MIT — see [`LICENSE`](LICENSE).

[![FOSSA Status](https://app.fossa.com/api/projects/git%2Bgithub.com%2Fthekitchencoder%2Fjclaim.svg?type=large)](https://app.fossa.com/projects/git%2Bgithub.com%2Fthekitchencoder%2Fjclaim?ref=badge_large)
