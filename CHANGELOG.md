# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
