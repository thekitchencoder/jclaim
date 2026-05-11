# JCLAIM

[![Maven Central](https://img.shields.io/maven-central/v/uk.codery/jclaim.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22uk.codery%22%20AND%20a:%22jclaim%22)
[![Build and Test](https://github.com/thekitchencoder/jclaim/actions/workflows/build.yml/badge.svg)](https://github.com/thekitchencoder/jclaim/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![FOSSA Status](https://app.fossa.com/api/projects/git%2Bgithub.com%2Fthekitchencoder%2Fjclaim.svg?type=shield)](https://app.fossa.com/projects/git%2Bgithub.com%2Fthekitchencoder%2Fjclaim?ref=badge_shield)
[![Java](https://img.shields.io/badge/Java-21+-blue.svg)](https://openjdk.org/projects/jdk/21/)

JCLAIM is an embeddable, Spring-independent Java library for **entity identity reconciliation**. Given identity claims about an entity from multiple source systems, JCLAIM returns a single stable canonical identifier — matching to one if the entity is already known, minting one if not.

The MDM (Master Data Management) entity-matching pattern, packaged as a library rather than an enterprise platform. Generic across entity types: configure one instance for Person, another for Vehicle, another for Customer — each with its own matching policy expressed in [JSPEC](https://github.com/thekitchencoder/jspec).

## Features

- **Canonical identity** — One stable URN per entity, minted as UUID v7. Source-system IDs become aliases on the canonical entity.
- **Human-friendly IDs** — Crockford Base32 with Damm check digit (`K7M2-9X4P-N`). Phone-readable, OCR-friendly, transcription-error-resistant.
- **Match-or-mint as one operation** — `resolveOrMint(claim)` returns a `Matched` or `Minted` result. Callers know which path was taken.
- **Matching policy as data** *(roadmap)* — Express your matching logic as a JSPEC specification. Tri-state evaluation surfaces `MATCHED`, `NOT_MATCHED`, and `UNDETERMINED` claims naturally.
- **Alias graph from day one** — Records the mapping from canonical identity to source IDs, with the data shape ready for merge, split, and federation correlation.
- **Conflict-aware** — When a match succeeds but stored attributes differ from the new claim, JCLAIM emits an event rather than silently updating. Evidence is preserved for stewardship.
- **Storage adapters** — In-memory in this release; MongoDB adapter follows. Alternative storage via a small port interface.
- **Spring-independent** — Works in plain Java applications; integrates with Spring Boot without depending on it.
- **Java 21 foundation** — Records, sealed interfaces, switch expressions, immutable collections throughout.

## Installation

```xml
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jclaim</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

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

### Reacting to conflicts

When a matching claim asserts attributes that disagree with the stored entity, JCLAIM emits an event:

```java
var resolver = DefaultEntityResolver.builder(new InMemoryEntityStorage())
        .conflictSink(event -> log.warn(
                "conflict on {}: {}", event.stored().id(), event.differences()))
        .build();
```

The stored entity is **not** updated — silent overwrites are explicitly avoided. Stewardship logic decides whether to overwrite, merge, branch, or escalate.

### Runnable example: retail customer reconciliation

A complete, runnable demonstration lives in
[`examples/RetailQuickStart.java`](examples/RetailQuickStart.java). It
loads five curated customers from the retail synthetic dataset under
[`src/test/resources/retail-fixtures/`](src/test/resources/retail-fixtures/),
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
mvn -q test-compile exec:java \
    -Dexec.mainClass=uk.codery.jclaim.examples.RetailQuickStart \
    -Dexec.classpathScope=test
```

The retail dataset itself covers around 100 synthetic customers across
four source systems and is documented in
[`src/test/resources/retail-fixtures/README.md`](src/test/resources/retail-fixtures/README.md).

### Other corpora

JClaim is entity-agnostic by design, so the test suite exercises the
same library against three different domains:

| Corpus      | Fixtures                                                                 | Example                                           |
|-------------|--------------------------------------------------------------------------|---------------------------------------------------|
| Customers   | [`retail-fixtures/`](src/test/resources/retail-fixtures/)                | [`RetailQuickStart`](examples/RetailQuickStart.java)   |
| Products    | [`product-fixtures/`](src/test/resources/product-fixtures/)              | [`ProductQuickStart`](examples/ProductQuickStart.java) |
| Properties  | [`property-fixtures/`](src/test/resources/property-fixtures/)            | [`PropertyQuickStart`](examples/PropertyQuickStart.java) |

Each corpus is ~100 ground-truth entities across four source systems
with the same scenario shape (single-source, multi-source, conflict
events, similar-looking-but-distinct entities). The library and the
test scaffolding are shared between them — only the YAML data and a
thin domain-named loader wrapper differ.

## Design

- **URN scheme** — `urn:<namespace>:entity:<UUID v7>`. The namespace is caller-configurable; the UUID is RFC 9562 v7 (time-ordered, B-tree-friendly) generated via [`uuid-creator`](https://github.com/f4b6a3/uuid-creator).
- **Human ID** — Eight Crockford Base32 characters plus a Damm check digit, displayed as `XXXX-XXXX-X`. Independently minted, never derived from the URN. Storage enforces uniqueness; the resolver re-rolls on collision.
- **Storage as a port** — `EntityStorage` exposes five operations: three reads, one atomic `resolveOrCreate` primitive (Mongo-shaped, maps to `findOneAndUpdate` upsert), and one atomic `addAlias`. The Mongo adapter in the next release fits this port without changing service code.
- **Alias-only match in this release** — `resolveOrMint` matches solely on the `(source, sourceId)` alias. A future release adds attribute-based matching driven by a [JSPEC](https://github.com/thekitchencoder/jspec) specification.

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

## Suite

JCLAIM is one of a family of small Java libraries developed under the `uk.codery` namespace:

- [JSPEC](https://github.com/thekitchencoder/jspec) — declarative criteria evaluation against JSON / YAML documents.
- JCLAIM — entity identity reconciliation (this project).

## License

MIT — see [`LICENSE`](LICENSE).

[![FOSSA Status](https://app.fossa.com/api/projects/git%2Bgithub.com%2Fthekitchencoder%2Fjclaim.svg?type=large)](https://app.fossa.com/projects/git%2Bgithub.com%2Fthekitchencoder%2Fjclaim?ref=badge_large)
