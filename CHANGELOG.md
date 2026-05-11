# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/thekitchencoder/jclaim/commits/main
