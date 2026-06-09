# ADR-0002: Public-ID generation as a policy — pluggable generator port with composable acceptance filtering

**Status:** Accepted
**Date:** 2026-06-07
**Deciders:** Chris (maintainer)

## Context

`publicId` is the opt-in, human-facing display ID minted alongside an entity's
URN at registration (`DefaultEntityResolver.freshPublicId()`). It is intended to
be **shared** — printed on letters and invoices, read aloud over a phone,
attached to a person — which makes its surface, not just its uniqueness, a
correctness concern.

Today `PublicIdGenerator` is a concrete `final` class. It draws entropy from a
`Supplier<Long>`, masks it through a `PublicIdFormat`, and renders the result as
Crockford Base32 plus a Damm check digit. The resolver holds one instance and
calls `generate()` in a loop that re-rolls **only** on storage collision.

Three problems surfaced.

**No content filtering, and the structural defences are weak.** A minted ID can
read as an obscene, defamatory, or discriminatory word. Crockford's *only*
obscenity mitigation is dropping the letter `U` (his spec lists it verbatim as
"Accidental obscenity"); the other exclusions (`I L O`) are confusability, not
safety. Leetspeak defeats the rest — Crockford's own decode aliases map `0→O`,
`1→I/L`, and readers fold `3→E`, `4→A`, `5→S`, so slurs and profanity remain
reachable (`5H4T`, `4R53`, `F46`, …). The per-ID probability is low, but the
downside is asymmetric: reputational and potentially litigious, most acutely for
IDs issued to people, and an issued ID cannot be recalled.

**One tolerance does not fit all deployments.** A government body minting person
IDs must be extremely defensive; a local business minting invoice numbers can be
lax. The *generation* scheme and the *risk tolerance* are independent axes that
vary per deployment — and, given multi-type mode, potentially per entity type
within one deployment.

**Generation itself is not pluggable.** Crockford is hard-baked. There is no
way to offer a vowel-resistant alphabet (Open Location Code's curated 20-symbol
set, selected specifically to avoid spelling words) or an entirely different
shape (a sequential counter, a UUID/ULID, an externally supplied id) without
forking the class.

This is the one identity-facing decision in the codebase that is **not yet a
port**. `MatchingPolicy`/`aliasOnly()`, `EntityStorage`, and `MatchEventSink`
are all already ports-with-a-sensible-default, swapped per deployment (see
ADR-0001). Promoting public-ID generation to the same shape is consistent with
the existing grain, not a new pattern.

## Decision

**1. Promote `PublicIdGenerator` to a minimal port.** A single-method
`@FunctionalInterface` — `String generate()`. The resolver depends on nothing
more. Everything else (alphabet, entropy source, length, check-digit scheme) is
an implementation detail of a *particular* generator, not a promise of the
contract.

```java
@FunctionalInterface
public interface PublicIdGenerator {
    /** A fresh candidate. Uniqueness is the resolver's concern; this need not be unique. */
    String generate();
}
```

**2. Treat generation and acceptance as two orthogonal policies.** *How to mint
a candidate* and *whether a candidate may be issued* vary independently. Keep
generation behind the port above; express acceptance as a composable
`Predicate<String>` (default `s -> true`, allow-all, composable via `.and()`).

**3. Rename the current class to `CrockfordPublicIdGenerator`.** It is, precisely,
a Crockford *generation* policy: random entropy → `PublicIdFormat` → Crockford +
Damm. An Open Location Code generator (`OlcPublicIdGenerator`) is its eventual
**sibling** — both exist, neither replaces the other.

**4. Apply acceptance by composition, not by forking.** A
`FilteringPublicIdGenerator` decorator wraps any delegate `PublicIdGenerator` plus
a predicate and re-rolls the delegate, within a bounded attempt budget, until the
predicate accepts. Because the decorator is *itself* a `PublicIdGenerator`, it
composes over any generation policy — Crockford now, OLC or sequential later —
without the port ever changing again. The resolver's default instance becomes
`FilteringPublicIdGenerator(new CrockfordPublicIdGenerator(...), allowAll)`. With
allow-all the decorator never re-rolls, so it is a transparent no-op and **today's
behaviour is preserved byte-for-byte**.

**5. Alphabet is a parameter of a generation impl, not a peer port.** The
sequential-id counterexample is the deciding test: a counter has no
alphabet-as-entropy-source and no fixed-bit shape, so an "alphabet policy" port
literally cannot express it. The pluggable seam therefore sits at *generation*;
alphabet selection (Crockford vs OLC-20 vs a reduced set) lives *inside* the
random/format family of generators.

**6. Acceptance gates generation, never validation.** `isValid`/`format()` stay
on the concrete `CrockfordPublicIdGenerator`, off the port. The filter runs at
mint time only — an ID issued under a laxer policy, or before a denylist grew,
must still validate. Validation must never consult the acceptance predicate.

**Scope of *this* ADR's change** is exactly steps 1–4 plus the wiring and test
fixups (see Action Items). OLC-20, real denylists, leetspeak normalisation, the
multilingual word-list module, per-type config keys, and the default-posture
change are deliberately deferred to isolated follow-ups that the port shape now
admits without further API churn.

## Options Considered

### Option A: Generation policy port + alphabet-as-parameter + acceptance decorator (CHOSEN)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low — extract a one-method interface, rename one class, add one decorator |
| Blast radius | `jclaim-core` id + resolver wiring; breaks `new PublicIdGenerator(...)` callers |
| Backward compatibility | Behavioural: full (allow-all decorator == historic mint). Source: breaks direct constructors, acceptable pre-1.0.0 |
| Cohesion | Generation behind the port; acceptance composed over it; alphabet stays an impl detail |

**Pros:** The contract is the smallest thing the resolver needs ("give me a
candidate"), so radically different generators — random, sequential, UUID,
external — coexist behind it. Acceptance composes over *any* of them as a
decorator, so the filter work lands later with zero further port change. Default
allow-all preserves behaviour exactly.
**Cons:** Two nested retry budgets (acceptance inside the decorator, uniqueness in
the resolver). Acceptable — they are different failure modes with different odds
and must not share a counter.

### Option B: Alphabet policy port (make only the alphabet pluggable)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low |
| Expressiveness | Cannot model non-alphabet generators |

**Pros:** Directly captures the Crockford↔OLC entropy-per-char trade-off.
**Cons:** Leaks the assumption that every ID is "random bits rendered through a
fixed-length alphabet." A sequential counter or UUID violates every part of that
and is unimplementable under the port. The alphabet is too narrow to be the seam.
Rejected.

### Option C: One combined `PublicIdPolicy` doing both generation and acceptance

| Dimension | Assessment |
|-----------|------------|
| Complexity | Med |
| Cohesion | Conflates two independently varying axes |

**Pros:** A single object to configure. **Cons:** You cannot reuse one generator
across tolerances, or one filter across generators — the exact mix-and-match the
gov-vs-invoice case needs. Forces every generator author to re-handle filtering.
Rejected.

### Option D: Bake the predicate into the resolver loop

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low |
| Invariant strength | Weak — generator can still emit unfiltered IDs standalone |

**Pros:** No new type; `freshPublicId()` just checks a predicate before the
uniqueness check. **Cons:** Couples acceptance to the resolver and gives a weaker
guarantee — anyone calling a generator outside the resolver gets unfiltered
output. The decorator makes "a configured generator cannot emit an unfiltered ID"
an intrinsic property. Rejected in favour of A.

### Option E: Do nothing (keep Crockford baked in)

**Pros:** Zero work. **Cons:** Leaves the obscenity exposure and offers no
risk-profile flexibility. Rejected.

## Trade-off Analysis

The central tension is **where the variability lives**. Pushing it into the
contract (Option B/C) forces every implementation to satisfy assumptions that
only the random/Crockford family actually holds — fixed length, an alphabet, an
entropy budget. Keeping the contract to a single `generate()` (Option A) makes
those assumptions impl-private, which is exactly what lets a sequential or
external generator sit behind the same port the resolver already consumes.

Acceptance is the second axis, and the decisive insight is that it need not be
part of the contract *at all*: a decorator that is itself a `PublicIdGenerator`
adds filtering by composition. So the API changes **once** — now — and both the
alphabet work and the filter work become later additions that never touch the
port again. Defaulting the predicate to allow-all means this enabling change is
behaviourally inert; the genuinely consequential decision (safe-by-default
baseline denylist vs permissive default) is deliberately decoupled and left to a
later ADR, where it can be argued on its own merits rather than smuggled in with a
refactor.

## Consequences

**Easier**
- Adding generation policies — OLC-20 (vowel-resistant), sequential, UUID/ULID,
  externally supplied — each as a small `PublicIdGenerator`.
- Adding acceptance filters of any strictness, composed via `Predicate.and()`,
  over *any* generator.
- Per-deployment **and per-type** tolerance: multi-type mode already gives each
  `jclaim.entity-types.<type>` its own `public-id.template`; a filter/generator
  slot drops in the same way (`entity-types.person.public-id.filter: strict`,
  `entity-types.invoice.public-id.filter: off`), and per-type physical isolation
  means those resolvers are genuinely independent beans.

**Harder / to revisit**
- Turning a concrete `final class` into an interface breaks anyone calling
  `new PublicIdGenerator(...)`. With `0.1.0` and `0.2.0` already on Maven Central
  this is a real source break, but acceptable under the pre-1.0.0 contract (the
  repo is on `0.3.0-SNAPSHOT`). It gets materially more expensive once `1.0.0`
  commits to API stability, so this is the moment to do it.
- The default-posture values call (safe-by-default vs permissive) is still open
  and intentionally out of scope here.
- Serious filtering needs reference data (multilingual profanity/slur lists) that
  core deliberately must not carry; it belongs in a sibling module, the
  `jclaim-matching-jspec` precedent.
- When non-Crockford generators land, the **Damm check radix must track the
  alphabet** (a base-20/24 alphabet needs a check digit defined over that radix,
  or a consistent decimal projection), or the check digit stops catching the
  transposition/substitution errors it exists for.

**Neutral**
- `publicId` stays opt-in: a `null` generator still means "mint no public ID."
- Uniqueness, alias atomicity, and stewardship-event behaviour are untouched. The
  resolver's `freshPublicId()` loop continues to own *only* the uniqueness re-roll;
  the acceptance re-roll lives inside the decorator.

**Storage and conformance contract — deliberate break**
- The combined public-ID rename **did** touch the `EntityStorage` port beyond the
  scope suggested by ADR-0001's grain. `findByHumanId` was renamed to
  `findByPublicId` on the port interface; all three storage adapters (in-memory,
  Postgres, MongoDB) and the abstract `EntityStorageContract` conformance suite
  were updated accordingly. The physical schemas also changed: Postgres column
  `human_id` → `public_id`, unique partial index `entities_human_id_unique` →
  `entities_public_id_unique`; MongoDB document field `humanId` → `publicId`,
  unique partial index `jclaim_humanId_unique` → `jclaim_publicId_unique`. This
  was chosen as a pre-1.0.0 "break once" clean-up — there are no external library
  consumers yet on Maven Central for the snapshot series that introduced these
  fields, so the rename is bounded. Existing `0.1.0`/`0.2.0` deployments that use
  the display-ID feature will need a column/field and index migration; none is
  shipped automatically. See the design spec at
  `docs/plans/2026-06-07-public-id-generator-port-design.md` for the full
  scope of the rename.

## Reference design

```java
// Port — the only thing the resolver depends on.
@FunctionalInterface
public interface PublicIdGenerator {
    String generate();
}

// Generation policy — today's class, renamed. Random entropy → format → Crockford.
// isValid()/format() remain here, off the port.
public final class CrockfordPublicIdGenerator implements PublicIdGenerator {
    private final PublicIdFormat format;
    private final Supplier<Long> entropy;
    // ... existing constructors/logic, unchanged ...
    @Override public String generate() { return format.format(entropy.get()); }
}

// Acceptance applied by composition. Itself a PublicIdGenerator, so it wraps
// any generation policy. Allow-all (the default) makes it a transparent no-op.
public final class FilteringPublicIdGenerator implements PublicIdGenerator {

    public static final Predicate<String> ALLOW_ALL = s -> true;

    private final PublicIdGenerator delegate;
    private final Predicate<String> acceptable;
    private final int maxAttempts;

    public FilteringPublicIdGenerator(PublicIdGenerator delegate,
                                     Predicate<String> acceptable,
                                     int maxAttempts) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.acceptable = Objects.requireNonNull(acceptable, "acceptable");
        this.maxAttempts = maxAttempts;
    }

    @Override
    public String generate() {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String candidate = delegate.generate();
            if (acceptable.test(candidate)) {
                return candidate;
            }
            // WARN: candidate rejected by acceptance policy; re-rolling.
        }
        throw new IllegalStateException(
                "No acceptable public ID after " + maxAttempts + " attempts");
    }
}
```

Default wiring (template configured): the resolver builder composes
`new FilteringPublicIdGenerator(new CrockfordPublicIdGenerator(format, entropy), ALLOW_ALL, n)`.
Blank/absent template still yields a `null` generator and no public ID.

## Action Items

**This change**
1. [x] Extract `PublicIdGenerator` as `@FunctionalInterface { String generate(); }`.
2. [x] Rename the concrete class to `CrockfordPublicIdGenerator`; keep
   `isValid`/`format()` on it, off the port.
3. [x] Add `FilteringPublicIdGenerator(delegate, Predicate<String>, maxAttempts)`
   with an `ALLOW_ALL` default predicate.
4. [x] Wire `DefaultEntityResolver.Builder` (and the Spring starter's template
   path) to compose `Filtering(Crockford, ALLOW_ALL)`; preserve null-means-no-id.
5. [x] Confirm `freshPublicId()` still owns only the uniqueness loop; the
   acceptance loop lives in the decorator.
6. [x] Fix tests that construct the old class; add decorator tests — allow-all
   no-op, a rejecting predicate re-rolls, budget exhaustion throws.

**Deferred (separate ADRs / efforts — enabled by, not part of, this change)**
7. [x] `OlcPublicIdGenerator` (vowel-resistant 20-symbol alphabet). Damm-radix
   handling: keep order-10 Damm over the base-20 value, render the check digit as
   a literal decimal `0`–`9` in the final position (data symbols stay pure OLC).
   `PublicIdFormat` made alphabet-parametric via the `IdAlphabet` strategy.
8. [ ] Denylist acceptance filter with leetspeak normalisation (strip hyphens;
   fold `0/1/3/4/5/7`) and a sibling module carrying multilingual lists.
9. [ ] Per-type config keys
   (`entity-types.<type>.public-id.{filter,generator}`).
10. [x] Decide the default posture: safe-by-default baseline denylist vs
    permissive allow-all. **Decided in
    [ADR-0003](0003-public-id-acceptance-default-posture.md): permissive default
    (allow-all) paired with a one-time unfiltered-posture WARN and an explicit
    opt-out.**

## Notes

This ADR follows the grain set by ADR-0001: a small port with a sensible default,
specialised per deployment. The structural background — Crockford's single-letter
"accidental obscenity" exclusion and Open Location Code's deliberate
vowel-exclusion to avoid spelling words — is what motivates treating alphabet as a
*generation* concern and acceptance filtering as a *separate, composable* one,
rather than relying on either alone.
