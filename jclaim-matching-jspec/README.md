# jclaim-matching-jspec

JSPEC-backed matching policy for [JCLAIM](../README.md). Supplies a
`MatchingPolicy` whose decisions are expressed as a
[JSPEC](https://github.com/thekitchencoder/jspec) specification rather
than hand-coded Java, so the rules that decide whether two records are
the same entity live in data you can review, version, and ship without
recompiling.

This module is the matching analogue of the storage adapters: the
`MatchingPolicy` **port** and the alias-only default live in
`jclaim-core` and carry no JSPEC dependency; the JSPEC binding ships
here as a swappable provider so an alternative engine could replace it
without touching core.

## Maven dependency

```xml
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jclaim-matching-jspec</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The module pulls `uk.codery:jspec` (0.6.0) onto the runtime classpath.
`jclaim-core` is depended on at compile scope.

## How it works

For each `(Claim, candidate)` pair the resolver hands the policy,
`JspecMatchingPolicy`:

1. **Projects** the pair into two documents via a `DocumentProjection`
   — a *target* (the claim) and a *context* (the candidate).
2. **Evaluates** the JSPEC `Specification` against them using jspec's
   two-arg `evaluate(target, context)`. Operands marked `$contextPath`
   late-bind against the candidate context, so a criterion can compare
   a claim value to the corresponding candidate value.
3. **Aggregates** the resulting `EvaluationOutcome` down to a jclaim
   `TriState` (`MATCHED` / `NOT_MATCHED` / `UNDETERMINED`) via an
   `OutcomeAggregator`.

The policy is immutable and thread-safe: a single `SpecificationEvaluator`
is built once from the spec and reused across every evaluation.

## Spec format — `$contextPath`

The default projection nests claim attributes under `claim` and
candidate attributes under `candidate`. A spec therefore queries
`claim.<name>` against the target and references the candidate's value
through the `$contextPath` sentinel:

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

`claim.email` is read from the target document; `{ $contextPath:
candidate.email }` resolves to the candidate's email from the context
document. The criterion matches when the two are equal.

## Usage

```java
import uk.codery.jclaim.matching.jspec.JspecMatchingPolicy;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

// From a classpath resource (YAML or JSON — YAML is a JSON superset):
var policy = JspecMatchingPolicy.fromResource("/matching/customer-match.yaml");

var resolver = DefaultEntityResolver.builder(new InMemoryEntityStorage())
        .namespace("codery")
        .matchingPolicy(policy)
        .maxCandidates(100)   // cap on the candidate pool the policy scores
        .build();
```

Three construction entry points:

| Method                                  | Use                                                       |
|-----------------------------------------|-----------------------------------------------------------|
| `JspecMatchingPolicy.fromResource(path)`| Load a YAML/JSON spec from the classpath. Missing resource → `IllegalArgumentException`. |
| `JspecMatchingPolicy.fromString(text)`  | Parse a YAML/JSON spec from an in-memory string.          |
| `JspecMatchingPolicy.builder()`         | Supply a pre-built `Specification` and override defaults.  |

## Defaults and overrides

`fromResource` / `fromString` use the default projection and
aggregator. To customise either, build a `Specification` yourself and
use the builder:

```java
var policy = JspecMatchingPolicy.builder()
        .spec(specification)
        .projection(myProjection)   // default: DocumentProjection.defaults()
        .aggregate(myAggregator)    // default: OutcomeAggregator.conjunctive()
        .build();
```

### Default projection — `DocumentProjection.defaults()`

Folds each side's `List<MatchingAttribute>` into a flat
`name -> value` map and nests it: `target = { "claim": {...} }`,
`context = { "candidate": {...} }`. On a duplicate attribute name the
**last** attribute in list order wins (a later assertion supersedes an
earlier one for projection purposes). Implement `DocumentProjection` to
reshape the documents — for example to flatten differently or to
include alias data.

### Default aggregation — `OutcomeAggregator.conjunctive()`

Collapses the JSPEC outcome summary conjunctively:

| Summary condition          | `TriState`     |
|----------------------------|----------------|
| any criterion `NOT_MATCHED`| `NOT_MATCHED`  |
| else any `UNDETERMINED`    | `UNDETERMINED` |
| else (all matched)         | `MATCHED`      |
| empty spec (zero criteria) | `UNDETERMINED` |

Implement `OutcomeAggregator` for a different collapse rule (e.g.
weighted or any-of disjunctive scoring).

## Relationship to the resolver

The resolver only ever sees the `uk.codery.jclaim.matching.MatchingPolicy`
port and a `TriState`; it never touches a JSPEC type. The exact-alias
short-circuit in `resolveOrMint` runs **before** the policy, so an
alias that already has an owner is matched without evaluating any spec —
the alias-atomic concurrency guarantee is preserved. The policy scores
only the deferred-resolution candidate pool, capped by
`maxCandidates`.

## Testing

```bash
mvn -pl jclaim-matching-jspec test
```

`JspecSmokeTest` pins the JSPEC 0.6.0 `$contextPath` integration; the
projection, aggregator, and end-to-end policy each have their own
suite.
