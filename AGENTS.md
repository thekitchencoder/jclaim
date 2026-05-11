# Repository Guidelines

## Project Structure & Module Organization

JClaim is a multi-module Maven project. The repository root holds the
aggregator POM; `jclaim-core` is the current implementation module and
sibling modules (`jclaim-storage-mongo`, `jclaim-storage-postgres`) are
on the roadmap.

Core library code lives under
`jclaim-core/src/main/java/uk/codery/jclaim`, organised by concern:
`model` (immutable records), `resolver` (the `EntityResolver` interface
and its default implementation), `storage` (the `EntityStorage` port
and its in-memory adapter under `storage.memory`), `event` (the
conflict event surface), and `id` (Crockford Base32, Damm checksum,
UUID v7 and human-friendly ID generation). Tests sit in
`jclaim-core/src/test/java/uk/codery/jclaim` mirroring the main package
layout. Runnable QuickStart examples live at the repository root
under `/examples/` and are added to `jclaim-core`'s test classpath
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
- **humanId** is the Crockford-Base32 display ID (e.g. `K7M2-9X4P-N`). It is
  separate from, and never derived from, the URN.

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
alias-miss, conflict detection, human-ID collision retry, URN-shape validation.
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
