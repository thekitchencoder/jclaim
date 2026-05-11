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
  - Immutable Java records throughout the domain model
  - `EntityResolver` service orchestrates resolve-or-mint against an
    `EntityStorage` port
  - In-memory storage adapter ships with the core module; a Mongo adapter
    arrives in a later module
  - Conflict events delivered via a pluggable `ConflictEventSink`

## Building and Running

Standard Maven commands:

- **Compile**:
  ```bash
  mvn compile
  ```
- **Run Tests**:
  ```bash
  mvn test
  ```
- **Build JAR**:
  ```bash
  mvn package
  ```
- **Build and install locally**:
  ```bash
  mvn clean install
  ```
- **Coverage report**:
  ```bash
  mvn test jacoco:report
  ```

## Development Conventions

- **Immutability**: All domain models are immutable Java 21 records.
  `EntityResolver` and `EntityStorage` implementations are thread-safe and
  enforce alias uniqueness atomically.
- **Match-or-mint contract**: `resolveOrMint(Claim)` returns a sealed
  `ResolutionResult` (`Matched` or `Minted`). Callers know which path was
  taken. In v0 the match is alias-driven; a future module will layer
  attribute-based matching via JSpec.
- **Conflict-aware**: When `resolveOrMint` matches but the stored attributes
  differ from the incoming claim, an `EntityAttributesConflicted` event is
  delivered to the configured `ConflictEventSink`. Stored attributes are not
  silently updated.
- **Spring-independent**: Core packages must not import
  `org.springframework.*`. JClaim integrates with Spring Boot without
  depending on it.
- **Testing**: JUnit 5 + AssertJ. Tests cover the happy path and the relevant
  failure mode (alias hit, alias miss, human-ID collision retry, conflict
  emission).
- **Logging**: SLF4J for all diagnostics; never `System.out` / `System.err`.

## Key Modules

- `uk.codery.jclaim.id` — Crockford Base32, Damm checksum, UUID v7
  generation, human-friendly ID minting.
- `uk.codery.jclaim.model` — `Entity`, `EntityId`, `Claim`, `Alias`,
  `SourceSystem`, `MatchingAttribute`, `ResolutionResult`.
- `uk.codery.jclaim.event` — `EntityAttributesConflicted`,
  `AttributeDiff`, `ConflictEventSink`.
- `uk.codery.jclaim.storage` — `EntityStorage` port, `StorageOutcome`,
  in-memory adapter under `storage.memory`.
- `uk.codery.jclaim.resolver` — `EntityResolver` interface,
  `DefaultEntityResolver` implementation.

## Roadmap

- **This module**: domain model, resolver, in-memory storage, human ID
  generation, conflict event sink.
- **Next**: MongoDB storage adapter.
- **After that**: matching policy DSL via JSpec composition; merge/split
  operations; retail synthetic dataset and integration tests; Maven Central
  publication.
