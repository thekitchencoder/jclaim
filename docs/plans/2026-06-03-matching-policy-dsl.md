# Matching Policy DSL Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a configurable, jspec-expressed **matching policy** to jclaim so `resolveOrMint` scores attribute-blocked candidates as `MATCHED`/`NOT_MATCHED`/`UNDETERMINED` and always returns a canonical identity, surfacing ambiguity as typed stewardship events.

**Architecture:** Above the storage port, jclaim gains a `MatchingPolicy` **port** (default `aliasOnly()`, reproducing today's behaviour exactly). This mirrors the existing `EntityStorage` port/adapter split: the **port + alias-only default live in `jclaim-core` and carry no jspec dependency**, while the jspec-backed implementation ships as a swappable provider module **`jclaim-matching-jspec`** (so an alternative — hand-coded, Drools, etc. — can replace it without touching core). The jspec provider projects each `(Claim, candidate)` pair into a `(targetDoc, contextDoc)` and evaluates a `Specification` whose `$contextPath` operands late-bind against the candidate context (jspec 0.6.0's two-arg `evaluate(target, context)`). An `OutcomeAggregator` collapses jspec's `EvaluationOutcome` to a `TriState`. The resolver runs the policy **only when no exact alias owner exists**; an exact alias match short-circuits to `Matched` so the alias-atomic concurrency guarantee is preserved. Ambiguity (`UNDETERMINED` everywhere, or multiple `MATCHED`) flows out as `MatchUndecided` / `MatchAmbiguous` events on a single renamed `MatchEventSink`.

**Module layout:**
- `jclaim-core` — `uk.codery.jclaim.matching`: `MatchingPolicy` port, `TriState`, `AliasOnlyMatchingPolicy`. **No jspec.** The resolver depends only on the port + `aliasOnly()`.
- `jclaim-matching-jspec` (NEW) — `uk.codery.jclaim.matching.jspec`: depends on `jclaim-core` (compile) + `jspec` (compile). Contains `DocumentProjection`, `OutcomeAggregator`, `JspecMatchingPolicy` and their defaults — every type that references a jspec type lives here.
- Starter depends on `jclaim-matching-jspec` as an **optional** dep and wires `JspecMatchingPolicy` only `@ConditionalOnClass`.

**Tech Stack:** Java 21, Maven multi-module, jspec 0.6.0 (`uk.codery:jspec`), JUnit 5 + AssertJ, Testcontainers (adapter limit tests), Spring Boot 3.5.x (starter).

**Source design (authoritative — deviations must update it):**
- `Efforts/entity-reconciliation-library/matching-policy-dsl.md` (brain) — 9 decisions, API sketch, 17 acceptance tests.
- `Efforts/entity-reconciliation-library/jspec-template-context-feature.md` (brain) — precursor requirements.

**Two refinements this plan locks (update the design doc when this lands):**
1. jspec operand syntax is **`$contextPath`** (doc wrote `$path` as a placeholder).
2. **Exact-alias short-circuit** precedes policy evaluation — preserves `resolveOrCreate` atomicity; makes `aliasOnly()` a true no-op change.

---

## Invariants to preserve (verify at start and end)

1. **Core stays dependency-light + Spring-independent** — `jclaim-core` must not import `org.springframework.*` **nor `uk.codery.jspec.*`**. jspec lives only in `jclaim-matching-jspec`. (Verify jspec 0.6.0 itself pulls no Spring, so the provider module stays Spring-free too.)
2. **Alias-atomic minting** — minting still routes through `storage.resolveOrCreate(alias, mintFn)`. The contract test `resolveOrCreate_concurrentCallsForSameAlias_produceExactlyOneEntity` must stay green.
3. **Back-compat for the default path** — with no policy configured, every existing core/adapter/corpus test passes unchanged.
4. **Never silent mint / never silent update** — ambiguous mints fire a typed event; stored attributes are never overwritten.
5. **Immutability + thread-safety** — all new model types are records/immutables; `MatchingPolicy.aliasOnly()` is a stateless shared singleton.

---

## Phase 0 — Dependency + jspec smoke test

### Task 0: Pre-flight — confirm jspec 0.6.0 is resolvable and Spring-free

**Step 1:** Confirm the release artifact exists.
Run: `mvn dependency:get -Dartifact=uk.codery:jspec:0.6.0` (repeat with `:0.6.0-SNAPSHOT` if the release is still propagating).
- If it resolves → proceed.
- If NOT yet published → fallback: build locally from the sibling repo:
  `mvn -f /Users/chris/projects/work/jspec/pom.xml -DskipTests clean install` (ensure that repo is on `main` with the merged `$contextPath` work, or on `feat/context-path-references`). Record which jspec ref was installed.

**Step 2:** Confirm jspec is Spring-free (protects Invariant 1).
Run: `mvn -f /Users/chris/projects/work/jspec/pom.xml dependency:tree | grep -i spring` → expect no compile/runtime Spring deps.

### Task 1: Manage jspec version + scaffold the `jclaim-matching-jspec` module

> jspec is declared in `<dependencyManagement>` (version) but added as a real dependency **only** in the new module — never in core.

**Files:**
- Modify: `pom.xml` (`<properties>`, `<dependencyManagement>`, `<modules>`)
- Create: `jclaim-matching-jspec/pom.xml`
- Create: `jclaim-matching-jspec/src/main/java/uk/codery/jclaim/matching/jspec/package-info.java`

**Step 1:** In aggregator `pom.xml` `<properties>`, add `<jspec.version>0.6.0</jspec.version>`.

**Step 2:** In aggregator `<dependencyManagement><dependencies>`, add (version managed centrally):
```xml
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jspec</artifactId>
    <version>${jspec.version}</version>
</dependency>
```

**Step 3:** Register the module in aggregator `<modules>` (after `jclaim-core`, before the starter so the starter can depend on it):
```xml
<module>jclaim-core</module>
<module>jclaim-matching-jspec</module>
<module>jclaim-storage-postgres</module>
<module>jclaim-storage-mongo</module>
<module>jclaim-spring-boot-starter</module>
```

**Step 4:** `jclaim-matching-jspec/pom.xml` inherits the parent and declares (GAV without version — managed by parent): compile dep on `jclaim-core`; compile dep on `uk.codery:jspec`; test deps mirroring core (junit, assertj); and `jclaim-core` tests-classifier test-jar if corpus/contract fixtures are reused. **No jspec in `jclaim-core/pom.xml`.**

**Step 5 (failing test → green):** Add `jclaim-matching-jspec/src/test/java/uk/codery/jclaim/matching/jspec/JspecSmokeTest.java` that builds a `Specification` with a `$contextPath` operand and asserts a two-arg `evaluate` returns `matched == 1`:
```java
Specification spec = new Specification("smoke", List.of(
    new QueryCriterion("same-email",
        Map.of("email", Map.of("$eq", Map.of("$contextPath", "candidate.email"))))));
var outcome = new SpecificationEvaluator(spec)
    .evaluate(Map.of("email", "a@b.com"),
              Map.of("candidate", Map.of("email", "a@b.com")));
assertThat(outcome.summary().matched()).isEqualTo(1);
```
Run: `mvn -pl jclaim-matching-jspec test -Dtest=JspecSmokeTest` → PASS. (If the jspec types differ from this sketch, fix the test against the real API before proceeding — this test is the canary that pins the integration.)

**Step 6:** Commit — `feat(matching-jspec): scaffold module + jspec 0.6.0 integration smoke test`.

---

## Phase 1 — TriState + MatchingPolicy + AliasOnly (in `jclaim-core`, no jspec)

> All types in `jclaim-core`, package `uk.codery.jclaim.matching`. This phase touches **zero** jspec — it pins the port and the back-compat default that the resolver depends on.

### Task 2: `TriState` enum
- Create `matching/TriState.java`: `enum TriState { MATCHED, NOT_MATCHED, UNDETERMINED }`.
- Trivial test asserting three values. Commit — `feat(core): TriState matching outcome enum`.

### Task 3: `MatchingPolicy` interface + `AliasOnlyMatchingPolicy`

**Files:** Create `matching/MatchingPolicy.java`, `matching/AliasOnlyMatchingPolicy.java`, test `matching/AliasOnlyMatchingPolicyTest.java`.

**Interface:**
```java
public interface MatchingPolicy {
    TriState evaluate(Claim claim, Entity candidate);
    static MatchingPolicy aliasOnly() { return AliasOnlyMatchingPolicy.INSTANCE; }
}
```
**Impl (stateless singleton):** `MATCHED` iff `candidate.aliases().contains(claim.asAlias())`, else `NOT_MATCHED`. Never `UNDETERMINED`.

**Tests (acceptance #1):**
- candidate carrying the claim's alias → `MATCHED`.
- candidate without it → `NOT_MATCHED`.
- never returns `UNDETERMINED`.

Commit — `feat(core): MatchingPolicy interface + alias-only default`.

---

## Phase 2 — Projection, Aggregator, JspecMatchingPolicy (in `jclaim-matching-jspec`)

> All Phase 2 types live in `jclaim-matching-jspec`, package `uk.codery.jclaim.matching.jspec`. `OutcomeAggregator` and `JspecMatchingPolicy` reference jspec types, so they cannot live in core; `DocumentProjection` is map-only but co-locates here since it's only used by the jspec policy.

### Task 4: `DocumentProjection` + default

**Files:** Create `jclaim-matching-jspec/.../jspec/DocumentProjection.java`, test.

```java
public interface DocumentProjection {
    record Projected(Map<String,Object> target, Map<String,Object> context) {}
    Projected project(Claim claim, Entity candidate);
    static DocumentProjection defaults() { return DefaultDocumentProjection.INSTANCE; }
}
```
Default: `target = { "claim": attrsToMap(claim.attributes()) }`, `context = { "candidate": attrsToMap(candidate.attributes()) }`, where `attrsToMap` folds `List<MatchingAttribute>` into `Map<String,Object>` (name→value; on duplicate names keep a deterministic rule — last wins, documented).

**Tests:** projection shape for claim+candidate; duplicate-attribute-name handling. Commit — `feat(core): document projection for matching`.

### Task 5: `OutcomeAggregator` + conjunctive default

**Files:** Create `jclaim-matching-jspec/.../jspec/OutcomeAggregator.java`, test.

```java
public interface OutcomeAggregator {
    TriState aggregate(EvaluationOutcome outcome);   // jspec type
    static OutcomeAggregator conjunctive() { return ConjunctiveAggregator.INSTANCE; }
}
```
Conjunctive rule over `outcome.summary()`: any `notMatched() > 0` → `NOT_MATCHED`; else any `undetermined() > 0` → `UNDETERMINED`; else `MATCHED`. (Empty spec / zero criteria → treat as `UNDETERMINED`; assert this edge in a test.)

**Tests (acceptance #2,3,4):** all-matched → MATCHED; one notMatched + one undetermined → NOT_MATCHED; one undetermined only → UNDETERMINED. Build the `EvaluationOutcome`s via real tiny specs so we test against jspec's actual summary semantics. Commit — `feat(core): conjunctive outcome aggregator`.

### Task 6: `JspecMatchingPolicy`

**Files:** Create `jclaim-matching-jspec/.../jspec/JspecMatchingPolicy.java`, test. Implements the core `uk.codery.jclaim.matching.MatchingPolicy` port.

```java
public final class JspecMatchingPolicy implements MatchingPolicy {
    public static Builder builder() { ... }
    public static JspecMatchingPolicy fromResource(String path) { ... } // load YAML/JSON spec
    public static JspecMatchingPolicy fromString(String specText) { ... }
    public static final class Builder {
        public Builder spec(Specification spec);
        public Builder projection(DocumentProjection p);   // default DocumentProjection.defaults()
        public Builder aggregate(OutcomeAggregator a);      // default OutcomeAggregator.conjunctive()
        public JspecMatchingPolicy build();
    }
    @Override public TriState evaluate(Claim claim, Entity candidate) {
        var p = projection.project(claim, candidate);
        var outcome = evaluator.evaluate(p.target(), p.context()); // shared SpecificationEvaluator
        return aggregator.aggregate(outcome);
    }
}
```
- Hold one `SpecificationEvaluator` (thread-safe, per jspec javadoc) built from the spec once.
- `fromResource`/`fromString` parse via jspec's spec loader (mirror how jspec's own tests load YAML specs — check jspec test sources for the loader entrypoint).

**Tests (acceptance #2,3, end-to-end):** a two-criterion spec using `$contextPath` operands → `MATCHED` for an aligned claim/candidate, `NOT_MATCHED`/`UNDETERMINED` for misaligned ones. Commit — `feat(core): jspec-backed matching policy`.

---

## Phase 3 — Port change: `findCandidates(Claim, int limit)`

> Decision 7. Cap candidate IO at the query level. Keep a one-arg convenience default to bound churn.

### Task 7: Extend the port + all three adapters + contract suite

**Files:**
- Modify port: `jclaim-core/.../storage/EntityStorage.java`
- Modify: in-memory, postgres, mongo adapters
- Modify contract: `jclaim-core/src/test/java/uk/codery/jclaim/storage/EntityStorageContract.java`
- Modify resolver interface/impl `findCandidates` passthrough.

**Step 1 (port):**
```java
Set<Entity> findCandidates(Claim claim, int limit);
default Set<Entity> findCandidates(Claim claim) { return findCandidates(claim, Integer.MAX_VALUE); }
```
Existing callers/tests using the one-arg form keep compiling.

**Step 2 (in-memory):** stop early once `candidates.size() == limit`.

**Step 3 (postgres):** add `LIMIT` to the attribute query and stop collecting at `limit` (alias owner counts toward the cap first).

**Step 4 (mongo):** add `.limit(limit)` to the `collection.find(query)` cursor.

**Step 5 (contract — acceptance #17):** add `findCandidates_respectsLimit`: store >N entities sharing an attribute, call `findCandidates(claim, k)`, assert result size ≤ k. Runs for all three adapters (in-memory inline; postgres/mongo via the existing Testcontainers bindings).

**Step 6:** `mvn test` (full reactor incl. Testcontainers) → green. Commit — `feat(core,storage): findCandidates honours a candidate cap`.

---

## Phase 4 — Stewardship events: rename + sealed hierarchy

> Decision 9. Breaking pre-1.0 rename `ConflictEventSink → MatchEventSink`; sealed `MatchEvent`. Mechanical but wide — keep it one clean phase.

### Task 8: Sealed `MatchEvent`, rename sink, reshape `EntityAttributesConflicted`

**Files (core):** `event/MatchEvent.java` (new sealed), `event/MatchEventSink.java` (rename of `ConflictEventSink`), `event/EntityAttributesConflicted.java` (reshape), delete `event/ConflictEventSink.java`. Update resolver references.

```java
public sealed interface MatchEvent
    permits EntityAttributesConflicted, MatchUndecided, MatchAmbiguous {}

public interface MatchEventSink {
    void accept(MatchEvent event);
    static MatchEventSink noop() { return e -> {}; }
}
```
Reshape `EntityAttributesConflicted` to implement `MatchEvent`. Target shape (design API sketch):
`(Entity stored, Claim claim, List<AttributeDiff> differingValues, Optional<EvaluationOutcome> policyOutcome)`.
- Drop `occurredAt` (events are fire-and-forget; if a timestamp is wanted, the sink stamps it). **Decision point to confirm with reviewer:** if `occurredAt` must stay for the Spring bridge, keep it as a 5th component. Default: drop per the doc sketch.
- Rename `incoming → claim`, `differences → differingValues`.

**Tests:** update `DefaultEntityResolverTest` conflict test + any event tests to the new shape/name. Commit — `refactor(core)!: MatchEventSink + sealed MatchEvent hierarchy`.

### Task 9: `MatchUndecided`, `MatchAmbiguous`, `CandidateOutcome` records

**Files:** `event/MatchUndecided.java`, `event/MatchAmbiguous.java`, `event/CandidateOutcome.java` (per the design API sketch — carry `candidatesConsidered/candidatesFound/candidatePoolTruncated`).
Unit tests: construction + defensive copies + null checks. Commit — `feat(core): MatchUndecided + MatchAmbiguous stewardship events`.

---

## Phase 5 — Narrow conflict semantics (Decision 6)

### Task 10: new-attribute-on-claim is additive, not a conflict

**Files:** Modify `DefaultEntityResolver.diff(...)` (currently lines ~182-188 add incoming-only attrs as diffs) to **omit** incoming-only attributes; only differing *values* for shared names produce an `AttributeDiff`. Update the existing test `resolveOrMint_matchingClaimWithDivergedAttributes_emitsConflictEvent` and add `resolveOrMint_claimAddsNewAttribute_doesNotEmitConflict` (acceptance #10).
Commit — `fix(core): claim-only attributes are additive, not conflicts`.

---

## Phase 6 — Resolver integration (the load-bearing phase)

> Wires policy + events + cap into `resolveOrMint`, preserving alias atomicity via the exact-alias short-circuit.

### Task 11: Builder slots

**Files:** Modify `DefaultEntityResolver.Builder`: add `matchingPolicy(MatchingPolicy)` (default `aliasOnly()`), `matchEventSink(MatchEventSink)` (default `noop()`, replaces `conflictSink`), `maxCandidates(int)` (default `100`). Keep `clock`. Wire fields into the constructor.
Tests: builder defaults. Commit — `feat(core): resolver builder slots for policy, sink, maxCandidates`.

### Task 12: New `resolveOrMint` control flow

**Files:** Modify `DefaultEntityResolver.resolveOrMint`.

**Control flow (exact):**
```
alias = claim.asAlias()
owner = storage.findByAlias(alias)
if owner present:                      // exact-alias short-circuit — identity link
    emitConflictIfDiverged(owner, claim, /*policyOutcome*/ empty)
    return Matched(owner)

// No exact alias owner → blocking + policy
candidates = storage.findCandidates(claim, maxCandidates)   // already excludes nothing by alias since none owns it
truncated = candidates.size() == maxCandidates  // see note
outcomes = candidates.map(c -> new CandidateOutcome(c, policy.evaluate(claim, c), <jspecOutcome or null>))
matched = outcomes.filter(MATCHED)

switch:
  matched.size() == 1:
     winner = matched[0].candidate
     attached = storage.addAlias(winner.id(), alias)   // atomic on alias index
     emitConflictIfDiverged(attached, claim, outcomeOf(winner))
     return Matched(attached)
  matched.size() > 1:
     winner = matched.min(by createdAt, then by urn)
     attached = storage.addAlias(winner.id(), alias)
     sink.accept(new MatchAmbiguous(claim, attached, others, outcomesMap, considered, found, truncated))
     emitConflictIfDiverged(attached, claim, outcomeOf(winner))
     return Matched(attached)
  matched.isEmpty():
     created = storage.resolveOrCreate(alias, () -> mintEntity(claim))  // atomic mint
     entity = created.entity() (or existing, race-safe)
     if any outcome == UNDETERMINED:
        sink.accept(new MatchUndecided(claim, entity, candidateOutcomes, considered, found, truncated))
     return Minted(entity)
```

**Concurrency notes (must hold):**
- The mint path still goes through `storage.resolveOrCreate` → atomicity preserved. If a concurrent caller minted the same alias between `findByAlias` and `resolveOrCreate`, `resolveOrCreate` returns `Existing` → return `Matched` (treat as race-won; no MatchUndecided since the alias now resolves).
- `addAlias` can throw `AliasAlreadyClaimed` if a concurrent mint grabbed the alias — catch, re-`findByAlias`, return `Matched(owner)`.
- **Truncation flag:** `size() == maxCandidates` is a heuristic; if exactness matters, query `limit+1` and trim. Decide in implementation; document the choice.

**Tests (acceptance #5,6,7,8,9):**
- one MATCHED → `Matched`, alias attached, no stewardship event.
- all UNDETERMINED → `Minted` + `MatchUndecided` carrying outcomes + truncation flag.
- multiple MATCHED → `Matched(oldest)` + `MatchAmbiguous`; tiebreak on URN when `createdAt` equal.
- `findCandidates` over cap → WARN log + `candidatePoolTruncated=true` in payload.
- `aliasOnly()` default → existing corpora/resolver tests unchanged (run retail/product/property corpus suites).

Commit — `feat(core): policy-driven resolveOrMint with deferred-resolution events`.

### Task 13: maxCandidates truncation observability

**Files:** WARN log in resolver when truncated (mirror alias/humanId collision logging). Micrometer counter is added in the starter phase (core stays Micrometer-free). 
Test: log assertion or a counter hook seam. Commit — `feat(core): warn on candidate-pool truncation`.

---

## Phase 7 — Spring Boot starter integration

> Decision 8 + 9. Property prefix `jclaim.matching.*`, sink rename `conflict-sink → match-sink`, event rename `JclaimConflictEvent → JclaimMatchEvent`, Micrometer truncation counter.

### Task 14: Rename sink wiring + event bridge
**Files:** `JclaimProperties` (`conflictSink → matchSink`, type enum), `JclaimAutoConfiguration` (`jclaimMatchEventSink` bean), `SpringEventConflictSink → SpringEventMatchSink`, `JclaimConflictEvent → JclaimMatchEvent` (wraps `MatchEvent`), `LoggingConflictSink → LoggingMatchSink`. Update `@EventListener` round-trip tests.
Commit — `refactor(starter)!: match-sink wiring + JclaimMatchEvent`.

### Task 15: `jclaim.matching.*` properties → policy bean
**Files:** add `jclaim-matching-jspec` as an **optional** dependency of the starter pom; add `Matching` nested props (`spec`, `maxCandidates`); `MatchingPolicy` bean `@ConditionalOnMissingBean`:
- no `spec` → `MatchingPolicy.aliasOnly()` (always available — it's in core, no conditional needed).
- `spec` set → `JspecMatchingPolicy.fromResource(...)` with defaults, gated `@ConditionalOnClass(JspecMatchingPolicy.class)`; **eager validation** (bad path → context fails to start). If `spec` is set but `jclaim-matching-jspec` is absent from the classpath, fail fast with a clear message naming the missing module (mirror the storage-adapter `@ConditionalOnClass` pattern).
- wire `matchingPolicy` + `maxCandidates` into the resolver bean.
Name the resolver bean (`@Bean("jclaimResolver")`) — preserve multi-resolver v2 path (Invariant from Decision 8).

**Tests (acceptance #11-15):** ApplicationContextRunner — default→aliasOnly; spec set→JspecMatchingPolicy; bean override wins; missing spec→startup failure; `match-sink.type=spring-events`→`@EventListener` receives `JclaimMatchEvent`. Commit — `feat(starter): jclaim.matching policy auto-configuration`.

### Task 16: Micrometer truncation counter
**Files:** decorate/observe truncation → `jclaim.matching.pool_truncated_total` (reuse the existing metrics decorator pattern from the starter). Test via `SimpleMeterRegistry`. Commit — `feat(starter): pool-truncation metric`.

---

## Phase 8 — Docs + design-doc reconciliation

### Task 17: Project docs
- Update root `README.md` + `jclaim-core` README: matching-policy section, `$contextPath` example spec, default-is-aliasOnly note.
- Update `CLAUDE.md`: matching DSL now delivered; jspec dependency; event rename; `findCandidates(Claim,int)`.
- `CHANGELOG`: breaking renames + new feature.
- Add a sample spec under `jclaim-spring-boot-starter/src/test/resources/matching/` used by the starter tests.
Commit — `docs: matching policy DSL`.

### Task 18: Update the brain design doc (record the two refinements)
In `Efforts/entity-reconciliation-library/matching-policy-dsl.md`: record (a) syntax resolved to `$contextPath`; (b) exact-alias short-circuit control flow; (c) `EntityAttributesConflicted` `occurredAt` decision; (d) **module split** — the design API sketch placed `JspecMatchingPolicy` under "Core"; implementation instead keeps the `MatchingPolicy` port + `aliasOnly()` in core and isolates the jspec binding in a `jclaim-matching-jspec` provider module (mirrors the `EntityStorage` port/adapter pattern; keeps jspec off core's classpath; lets the policy be implemented differently). Per the memory rule, design deviations update the doc, not just the code.

---

## Final verification (before finishing the branch)

- `mvn clean install` — full reactor green (incl. Testcontainers adapter tests).
- `grep -RIn "springframework\|uk.codery.jspec" jclaim-core/src/main` → no matches (Invariant 1: core has neither Spring nor jspec).
- `grep -n "jspec" jclaim-core/pom.xml` → no matches (jspec only in `jclaim-matching-jspec`).
- Run all three corpus QuickStarts (retail/product/property) — behaviour unchanged under default policy.
- `grep -RIn "ConflictEventSink\|JclaimConflictEvent\|conflict-sink" --include=*.java --include=*.md .` → no stale references.
- Confirm `resolveOrCreate_concurrentCallsForSameAlias_produceExactlyOneEntity` still green (Invariant 2).
