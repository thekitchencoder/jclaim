# Repository Guidelines

## Project Structure & Module Organization

JClaim is a multi-module Maven project. The repository root holds the
aggregator POM (`packaging=pom`, centralising dependency/plugin versions
and the modules list); each capability ships as its own module:
`jclaim-core` (storage + matching ports, in-memory adapter, abstract
conformance suite), `jclaim-matching-jspec` (JSPEC-backed
`MatchingPolicy`), `jclaim-storage-postgres` and `jclaim-storage-mongo`
(database adapters), and `jclaim-spring-boot-starter` (Spring Boot 3.x
auto-configuration).

Core library code lives under
`jclaim-core/src/main/java/uk/codery/jclaim`, organised by concern:
`model` (immutable records), `resolver` (the `EntityResolver` interface,
`DefaultEntityResolver`, and the `EntityResolvers` multi-type registry),
`storage` (the `EntityStorage` port and its in-memory adapter under
`storage.memory`), `matching` (the `MatchingPolicy` port, `TriState`,
and the `aliasOnly()` default), `event` (the stewardship event surface —
sealed `MatchEvent` delivered to `MatchEventSink`), and `id` (Crockford
Base32, Damm checksum, UUID v7 and public-ID generation — `PublicIdGenerator`
port, `CrockfordPublicIdGenerator`, `FilteringPublicIdGenerator`). Tests
sit in `jclaim-core/src/test/java/uk/codery/jclaim` mirroring the main
package layout; `jclaim-core` publishes a `tests`-classifier jar so
adapter modules inherit the `EntityStorageContract` conformance suite and
corpus contracts. Runnable QuickStart examples live at the repository
root under `/examples/` and are added to `jclaim-core`'s test classpath
via `build-helper-maven-plugin`. Skim `README.md` and `CLAUDE.md`
before altering behaviour.

## Terminology & Naming

- The project is **JClaim**. Artifact coordinates, package names, and logging
  identifiers all use `uk.codery.jclaim`.
- Use **entity / claim / alias / source system / matching attribute**.
  Never specialise to a concrete domain (Person, Customer, Vehicle) in core
  code — specialisations belong in caller projects.
- **Match** and **Mint** are the two outcomes of `resolveOrMint` — keep these
  terms in code, comments, and docs.
- **TriState** (`MATCHED` / `NOT_MATCHED` / `UNDETERMINED`) is the
  matching-policy verdict for one candidate; it lives in `jclaim-core` and
  carries no jspec coupling.
- **publicId** is the opt-in Crockford-Base32 display ID (e.g. `K7M2-9X4P-3`),
  minted only when a template is configured. It is separate from, and never
  derived from, the URN. Generation is pluggable via `PublicIdGenerator`;
  `CrockfordPublicIdGenerator` is the built-in default, wrapped by
  `FilteringPublicIdGenerator` (allow-all) for composable content acceptance.

## Build, Test, and Development Commands

Run from the repository root; commands walk the reactor unless `-pl`
narrows the scope.

- `mvn clean verify` — full reactor build plus unit suite plus coverage
  check; run before pushing.
- `mvn test` — quickest way to re-run JUnit/AssertJ tests across every
  module while iterating.
- `mvn -pl jclaim-core test -Dtest=DefaultEntityResolverTest` — focused
  single-class run in one module.
- `mvn -DskipTests package` — produces local JARs when you only touch
  docs or metadata (avoid for functional work).

Requires Java 21, Maven 3.6+, and Lombok annotation processing enabled
in your IDE. After pulling a structural change, re-import the Maven
project in your IDE so the new modules register.

## Coding Style & Naming Conventions

Follow the conventions from `CONTRIBUTING.md`: 4-space indentation,
120-character lines, K&R braces, no wildcard imports. Prefer Java 21 records,
sealed interfaces, and immutable collections. Use factory helpers (`List.of`,
`Map.of`) and descriptive identifiers (`claim`, `storedEntity`,
`resolutionResult`). Package names stay under `uk.codery.jclaim.<feature>`.
Classes end in `*Resolver`, `*Storage`, `*Sink`, `*Generator`, or `*Result`
to signal their layer.

## Testing Guidelines

Write focused JUnit Jupiter tests in
`jclaim-core/src/test/java/uk/codery/jclaim/...` mirroring the main
package layout. Use AssertJ's fluent assertions. Cover the
happy path **and** the relevant failure mode for each operation: alias-hit,
alias-miss, conflict detection, public-ID collision retry, URN-shape validation.
Keep test data deterministic where possible — inject `Clock` or
`Supplier<UUID>` rather than calling `Instant.now()` / `UUID.randomUUID()`
directly inside hot paths. Run `mvn test` locally; add `-Dtest=ClassName` for
targeted runs.

## Commit & Pull Request Guidelines

Use conventional commit prefixes (`feat`, `fix`, `perf`, `chore`, `docs`,
`test`, `refactor`) with an optional scope (`feat(storage): add postgres
adapter`). Each PR should describe the motivation, link the relevant issue,
enumerate testing performed, and call out any follow-ups so maintainers can
merge confidently. Keep commits small; include tests alongside code.

## Security & Configuration Tips

Never commit live credentials; sample fixtures belong in
`jclaim-core/src/test/resources`.
Keep SLF4J calls structured (no full document dumps); redact attribute values
that may carry PII before adding diagnostics. The library is thread-safe by
construction; preserve that property — storage adapters must enforce alias
uniqueness atomically rather than read-then-write.
