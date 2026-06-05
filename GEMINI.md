# Gemini Context: JClaim

This document provides an overview of the JClaim project as instructional
context for Gemini.

## Project Overview

JClaim is an embeddable, Spring-independent Java 21 library for entity
identity reconciliation. Given identity claims about an entity from multiple
source systems, JClaim returns a single stable canonical identifier — matching
to one if the entity is already known, minting one if not.

- **Purpose**: A reusable, generic implementation of the MDM (Master Data
  Management) entity-matching pattern, packaged as a library that gets
  embedded inside a domain service rather than adopted as a platform.
- **Core Technologies**:
  - **Language**: Java 21
  - **Build**: Apache Maven
  - **Dependencies**:
    - `com.github.f4b6a3:uuid-creator` — UUID v7 generation (RFC 9562)
    - `org.projectlombok:lombok` — boilerplate reduction (provided scope)
    - `org.slf4j:slf4j-api` — logging
- **Architecture**:
  - Multi-module Maven project. Aggregator POM at the repository root
    centralises versions and the modules list; five published modules:
    `jclaim-core`, `jclaim-matching-jspec`, `jclaim-storage-postgres`,
    `jclaim-storage-mongo`, and `jclaim-spring-boot-starter`.
  - Immutable Java records throughout the domain model
  - `EntityResolver` service orchestrates resolve-or-mint against an
    `EntityStorage` port
  - In-memory storage adapter ships with `jclaim-core` for testing and
    evaluation; the MongoDB and PostgreSQL adapters ship as sibling
    modules. Every adapter passes the abstract `EntityStorageContract`
    suite published from `jclaim-core`'s `tests`-classifier jar.
  - Matching is a port (`MatchingPolicy` → `TriState`) with an
    `aliasOnly()` default in core; the JSPEC-backed implementation
    ships in `jclaim-matching-jspec`.
  - Stewardship events delivered via a pluggable `MatchEventSink`

## Building and Running

Run from the repository root; Maven walks the reactor unless `-pl`
narrows the scope.

- **Compile every module**:
  ```bash
  mvn compile
  ```
- **Run tests across every module**:
  ```bash
  mvn test
  ```
- **Build JARs for every module**:
  ```bash
  mvn package
  ```
- **Build and install everything locally**:
  ```bash
  mvn clean install
  ```
- **Coverage report (per module, under each `<module>/target/site/jacoco`)**:
  ```bash
  mvn test jacoco:report
  ```
- **Focus on a single module**:
  ```bash
  mvn -pl jclaim-core test
  ```

## Development Conventions

- **Immutability**: All domain models are immutable Java 21 records.
  `EntityResolver` and `EntityStorage` implementations are thread-safe and
  enforce alias uniqueness atomically.
- **Match-or-mint contract**: `resolveOrMint(Claim)` returns a sealed
  `ResolutionResult` (`Matched` or `Minted`). Callers know which path was
  taken. An exact `(source, sourceId)` alias owner short-circuits to
  `Matched`; otherwise a capped candidate pool is scored by the configured
  `MatchingPolicy` (`aliasOnly()` by default, JSPEC via
  `jclaim-matching-jspec`) to a `TriState` and the resolver matches or mints
  accordingly.
- **Stewardship events**: The resolver always returns an identity; ambiguity
  surfaces as events on the configured `MatchEventSink`. The sealed
  `MatchEvent` has three variants — `EntityAttributesConflicted` (a matched
  entity's stored attributes disagree with the claim), `MatchUndecided` (a
  mint left a candidate `UNDETERMINED`), and `MatchAmbiguous` (several
  candidates matched; oldest wins). Stored attributes are never silently
  updated; evidence is preserved for stewardship. Events carry `TriState`
  only — never jspec types.
- **Spring-independent**: Core packages must not import
  `org.springframework.*`. JClaim integrates with Spring Boot without
  depending on it.
- **Testing**: JUnit 5 + AssertJ. Tests cover the happy path and the relevant
  failure mode (alias hit, alias miss, human-ID collision retry, conflict
  emission).
- **Logging**: SLF4J for all diagnostics; never `System.out` / `System.err`.

## Key Packages

Inside `jclaim-core`:

- `uk.codery.jclaim.id` — Crockford Base32, Damm checksum, UUID v7
  generation, human-friendly ID minting.
- `uk.codery.jclaim.model` — `Entity`, `EntityId`, `Claim`, `Alias`,
  `SourceSystem`, `MatchingAttribute`, `ResolutionResult`.
- `uk.codery.jclaim.matching` — `MatchingPolicy` port, `TriState`,
  `AliasOnlyMatchingPolicy` (the stateless `aliasOnly()` default).
- `uk.codery.jclaim.event` — sealed `MatchEvent`
  (`EntityAttributesConflicted` / `MatchUndecided` / `MatchAmbiguous`),
  `AttributeDiff`, `CandidateOutcome`, `MatchEventSink`.
- `uk.codery.jclaim.storage` — `EntityStorage` port, `StorageOutcome`,
  in-memory adapter under `storage.memory`.
- `uk.codery.jclaim.resolver` — `EntityResolver` interface,
  `DefaultEntityResolver`, and the `EntityResolvers` multi-type registry.

Sibling modules:

- `jclaim-matching-jspec` — `uk.codery.jclaim.matching.jspec`;
  `JspecMatchingPolicy` (jspec `0.7.0`, `$contextPath` operands).
- `jclaim-storage-postgres` / `jclaim-storage-mongo` — JDBC and Mongo
  `EntityStorage` adapters.
- `jclaim-spring-boot-starter` — Spring Boot 3.x auto-configuration.

## Status

All five modules are delivered: domain model, resolver, stewardship
events, in-memory + MongoDB + PostgreSQL storage (each passing the shared
`EntityStorageContract`), opt-in human IDs, the `MatchingPolicy` port with
the JSPEC-backed implementation, multiple-entity-type support
(`jclaim.entity-types.<type>` with physical per-type isolation and the
`EntityResolvers` facade), and the Spring Boot starter. Next milestone:
merge / split operations.
