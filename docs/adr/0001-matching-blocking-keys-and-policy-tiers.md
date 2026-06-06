# ADR-0001: Matching architecture — separating blocking from scoring, and deterministic vs probabilistic policies

**Status:** Accepted (blocking/scoring separation); probabilistic provider Proposed
**Date:** 2026-06-06
**Deciders:** Chris (maintainer)

## Context

`resolveOrMint(Claim)` resolves an identity in two stages once the exact
`(source, sourceId)` alias short-circuit misses:

1. **Block** — `EntityStorage.findCandidates(claim, maxCandidates)` returns a
   capped candidate pool.
2. **Score** — the `MatchingPolicy` scores each candidate to a `TriState`
   (`MATCHED` / `NOT_MATCHED` / `UNDETERMINED`), and the resolver matches, mints,
   or raises a stewardship event.

Two problems surfaced with the original design.

**A `MatchingAttribute` was overloaded.** The shipped `findCandidates` blocks on
*every* `(name, value)` attribute on the claim by exact equality. There was no
way to carry an attribute that the policy *scores on* but that does *not*
*block* — to be visible to the policy an attribute must be stored, and anything
stored became a blocking key. So a single field played three roles at once:
stored data, blocking key, and scoring evidence, with no way to tune them apart.

**This conflation has teeth because of the cap.** Blocking is a disjunctive
union, so adding a weak attribute (e.g. `town`) only ever *enlarges* the pool —
but the pool is capped at `maxCandidates` (default 100). A low-cardinality key
like `town=London` can fill the cap with thousands of Londoners and **truncate
the genuine strong-key match out of the pool before it is ever scored**, causing
a silent false mint. The resolver detected truncation but only logged a `WARN`;
it never surfaced as a `MatchEvent`.

**The weak-signals case made the gap unavoidable.** An external supplier may
provide only `name`, `address`, and `dateOfBirth` — no strong identifier. These
must be *scored* (probabilistically), and they must *not* be blocked on by raw
exact equality (typos, nicknames, address drift, transposed digits all defeat
equality). That requires exactly the separation the old model could not express.

## Decision

**1. Separate blocking keys from scored attributes (implemented).**
`MatchingPolicy` gains `Set<String> blockingKeys()` (default empty). When
non-empty, the resolver fetches the pool on a *projection* of the claim
(`Claim.projectedTo(keys)`) but scores candidates against the **full** claim. An
attribute outside `blockingKeys()` is therefore scored but never widens — and
never truncates — the pool. Empty default preserves historic "block on
everything" behaviour. No `EntityStorage` port change, no adapter change, no
`EntityStorageContract` change.

**2. Adopt a two-tier matching taxonomy (deterministic vs probabilistic).**
Both tiers implement the same `MatchingPolicy` port and return `TriState`; they
differ in how candidates are blocked and scored:

- **Deterministic** — strong, trusted identifiers (NINO, NHS number, a rotating
  token). Exact-equality blocking on the identifier; decisive scoring (agreement
  → `MATCHED`, trusted conflict → `NOT_MATCHED`, positive-only treatment for
  rotating/volatile ids). Expressible as a jspec spec.
- **Probabilistic** — weak signals only (name, address, DoB). *Derived* blocking
  keys (phonetic + DoB, DoB + postcode prefix) run as multiple passes; weighted
  similarity scoring cut by **two** thresholds onto `TriState`, with the middle
  band routed to clerical review via `MatchUndecided`. Hand-written Java (or a
  dedicated provider module), **not** jspec — weighted sums with thresholds do
  not express naturally in a boolean/Kleene spec.

## Options Considered

### Option A: `blockingKeys()` on `MatchingPolicy` + claim projection (CHOSEN)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low — one default method, one record helper, three resolver lines |
| Blast radius | Core only; no port/adapter/contract change |
| Backward compatibility | Full — empty default == historic behaviour |
| Cohesion | Blocking keys and scoring live on the same object |

**Pros:** Blocking and scoring — the two halves of one matching strategy — are
declared together, so they cannot drift out of sync (the failure mode that bites
when normalisation differs between a blocking config and a scoring policy). No
storage surface touched. Trivially reversible.
**Cons:** The policy now carries a retrieval concern (which attribute names to
fetch on), not purely a scoring concern. Acceptable: both are facets of one
matching strategy.

### Option B: Blocking-key allowlist configured on the storage adapter

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low–Med — per-adapter builder option |
| Blast radius | Every adapter + the in-memory reference |
| Cohesion | Splits matching config across resolver (policy) and storage (keys) |

**Pros:** Keeps the blocking concern physically in the component that runs the
query. **Cons:** The *values* (which names) are a matching-strategy decision but
would be configured at storage-construction time, far from the policy — exactly
the drift risk we want to avoid. Must be implemented (and kept consistent) in
every adapter.

### Option C: Change the port — `findCandidates(Claim, Set<String> keys, int)`

| Dimension | Assessment |
|-----------|------------|
| Complexity | Med — port signature, every adapter, the contract |
| Blast radius | Wide — public port break |

**Pros:** Most explicit; storage is told precisely what to block on. **Cons:**
Breaks the `EntityStorage` port and forces every adapter and the conformance
contract to change, for a result Option A achieves by projecting the claim the
adapter already accepts. Not worth the surface area.

### Option D: Role flag on `MatchingAttribute` (`BLOCKING` / `EVIDENCE` / `BOTH`)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Med — model + every adapter's storage/serialisation |
| Cohesion | Wrong home — role is a per-resolver decision, not a per-value fact |

**Pros:** Per-attribute control. **Cons:** The same `town` field may block in one
domain and not another — role is a property of the *matching strategy*, not of
the attribute value. Pollutes the core model and all storage schemas. Rejected.

## Trade-off Analysis

The central tension is **where the blocking-key decision lives**. It is
simultaneously a retrieval concern (executed by storage) and a matching-strategy
concern (decided alongside scoring). Co-locating it with the policy (Option A)
prevents the blocking/scoring drift that a split configuration (B) or a per-value
flag (D) invites, and does so without the public port break of (C). The claim
projection is the key insight: the adapter keeps its existing
`findCandidates(Claim, int)` signature and simply receives a narrower claim, so
"what to block on" is expressed entirely in core, in the policy, with zero
storage change.

For the **deterministic vs probabilistic** split, the decisive factor is that
both fit the *same* `TriState` port — the three-valued return already models
"insufficient evidence", which is the clerical-review band probabilistic linkage
requires and the "no shared strong id" outcome deterministic matching requires.
The tiers diverge only in blocking-key derivation and scoring math, both of which
live behind the policy interface. So no new core abstraction is needed; the
taxonomy is a usage pattern plus (eventually) a second provider module.

## Consequences

**Easier**
- Carrying weak signals as scored-but-not-blocked evidence (the original gap).
- Weak-signal / probabilistic matching: derived blocking keys + weighted scoring
  drop in behind the existing port.
- Reasoning about pool size — blocking keys are now an explicit, high-cardinality
  choice rather than an accident of which attributes happen to be present.

**Harder / to revisit**
- Truncation is still only a `WARN` log. Promote it to a first-class `MatchEvent`
  so an over-coarse blocking key becomes observable (see Action Items) — this is
  the safety net for the very failure this ADR removes the *cause* of.
- Normalisation discipline now spans two places that must agree: the blocking-key
  values (computed at write and claim time) and the scored values. If a derived
  key is computed differently on write vs claim, the block silently misses and
  the pair never reaches scoring. Normalise at the boundary, once.
- Probabilistic scoring needs reference data (postcode formats, nickname tables,
  phonetic algorithms) that core deliberately does not ship.

**Neutral**
- The `(source, sourceId)` alias remains the permanent link. Both tiers pay their
  blocking/scoring cost only at *first contact*; every subsequent event from a
  linked source short-circuits on the alias regardless of tier.

### Reference design — probabilistic (weak-signal) policy

Derived blocking keys computed at the boundary (stored as `blk_*` attributes,
blocking-only) plus weighted scoring over the raw fields (scoring-only),
two thresholds mapping onto `TriState`:

```java
public final class WeakSignalMatchingPolicy implements MatchingPolicy {

    // Multiple passes: any one key can be corrupted by the one typo that matters,
    // so recall comes from running several. All are DoB-anchored (DoB is the
    // highest-cardinality, most stable of the three weak fields).
    @Override
    public Set<String> blockingKeys() {
        return Set.of("blk_dob_postcode3", "blk_meta_dobyear");
    }

    @Override
    public TriState evaluate(Claim claim, Entity candidate) {
        Map<String, String> c = attrs(claim);
        Map<String, String> e = attrs(candidate);

        double score = 0;
        score += weight(jaroWinkler(c.get("surname"),  e.get("surname")),  6.0, -4.0);
        score += weight(jaroWinkler(c.get("forename"), e.get("forename")), 4.0, -2.0);
        score += dobAgreement(c.get("dob"), e.get("dob"));   // exact +8, ±1 digit +3, else -6
        score += weight(addressSim(c.get("address"), e.get("address")), 3.0, 0.0); // drifts: no penalty

        if (score >= MATCH_THRESHOLD)     return TriState.MATCHED;       // auto-link
        if (score <= NON_MATCH_THRESHOLD) return TriState.NOT_MATCHED;   // distinct
        return TriState.UNDETERMINED;                                    // clerical review
    }
}
```

Key tuning rules: bias to mint-and-flag, never auto-merge on a coin-flip (narrow
`MATCHED`, widen `UNDETERMINED` — a wrong merge of two people is worse, and
harder to undo, than a duplicate); penalise DoB disagreement but **not** address
disagreement (people move); size `maxCandidates` to the combined blocking-key
cardinality, and watch population skew (common surname + common DoB buckets).

## Action Items

1. [x] Add `MatchingPolicy.blockingKeys()` (default empty) and
   `Claim.projectedTo(Set<String>)`.
2. [x] Wire claim projection into `DefaultEntityResolver.resolveOrMint` (block on
   projection, score on full claim).
3. [x] Tests: weak attribute widens the pool under the default; under
   `blockingKeys={...}` the weak-only entity is excluded yet still scored.
4. [ ] Promote the `truncated` flag from `WARN` log to a first-class
   `MatchEvent` (`CandidatePoolTruncated` or a field on existing events).
5. [ ] Document the deterministic vs probabilistic tiers and the
   normalise-at-the-boundary rule in the matching README.
6. [ ] Decide whether probabilistic scoring ships as a provider module
   (`jclaim-matching-probabilistic`) alongside `jclaim-matching-jspec`, including
   pluggable similarity/phonetic functions.
7. [ ] Confirm storage blocking indexes cover every derived `blk_*` key name a
   probabilistic policy declares (Postgres `(name, value)` index; Mongo
   attributes index).

## Notes

The `(source, sourceId)` **alias** is the permanent identity link and the
short-circuit that precedes all blocking/scoring; nothing in this ADR changes it.
Blocking and scoring only run for a claim whose alias is not yet owned — i.e. at
first contact — after which the alias carries the identity for free.
