# ADR-0003: Public-ID acceptance default posture — permissive default with a discoverability nudge

**Status:** Accepted
**Date:** 2026-06-09
**Deciders:** Chris (maintainer)

## Context

ADR-0002 promoted public-ID *generation* to a port and added acceptance
filtering as a composable `Predicate<String>` decorator
(`FilteringPublicIdGenerator`), defaulting the predicate to allow-all so the
enabling refactor was behaviourally inert. It deliberately left one question
open — its own item 10:

> Decide the default posture: safe-by-default baseline denylist vs permissive
> allow-all.

That call was decoupled on purpose, "so it can be argued on its own merits
rather than smuggled in with a refactor." This ADR makes it.

The decision is genuinely a values/risk call, and it is constrained by a line
ADR-0002 drew firmly: **core must not carry reference data.** Serious acceptance
filtering needs multilingual profanity/slur lists, which belong in a sibling
module (the `jclaim-matching-jspec` precedent), not in `jclaim-core`. That
constraint bounds what "safe-by-default" can even *mean* in core — a genuinely
safe default would require either an embedded word-list in core (violating the
line) or behaviour that flips on the presence of a not-yet-existing module
(surprising and implicit).

The countervailing pressure is real and was the whole motivation for the port:
a `publicId` is **shared** — printed on letters and invoices, read aloud, attached
to a person — and the downside of an obscene or defamatory ID is asymmetric
(reputational, potentially litigious) and **un-recallable** once issued. A
permissive default therefore risks *silent* exposure: an integrator mints person
IDs, never learns the filter exists, and ships unfiltered IDs in production.

So the posture decision is inseparable from a second question — if the default is
permissive, what stops the exposure from being silent?

## Decision

**1. The default acceptance posture is permissive (allow-all), everywhere.**
The resolver's default acceptance predicate remains
`FilteringPublicIdGenerator.ALLOW_ALL`. No core word-lists, no behaviour change,
the historic mint preserved byte-for-byte. Acceptance filtering — including any
baseline denylist — is **explicit opt-in**: the integrator adds the `jclaim-filter`
sibling module (ADR-0002 item 8, when it lands) and wires a filter deliberately,
or configures one via the starter. The risk-tolerance call belongs to the
deployment, not to the library.

This is consistent with the grain set by ADR-0001 and ADR-0002: a small port
whose *default is the historic behaviour*, specialised per deployment. It keeps
core free of reference data and changes nothing for existing `0.1.0`/`0.2.0`
users of the display-ID feature.

**2. A permissive default ships with a one-time discoverability nudge, in core.**
Because the exposure is asymmetric and un-recallable, "permissive" must not mean
"silent." When a publicId template *is* configured but the acceptance predicate
is the allow-all default and the integrator has **not** explicitly acknowledged
unfiltered IDs, the resolver logs a single SLF4J **WARN** at build time:

> publicId template configured but no acceptance filter — public IDs are minted
> unfiltered and cannot be recalled once issued. Configure an acceptance filter,
> or call `allowUnfilteredPublicIds()` / set `jclaim.public-id.filter: off` to
> acknowledge this and silence the warning.

The nudge lives in **`jclaim-core`** (`DefaultEntityResolver.Builder.build()`),
not only in the Spring starter — Spring-free embedders mint public IDs too and
deserve the same signal. The message points at documentation, **not** at the
`jclaim-filter` module by name (it does not yet exist; naming it would date the
log).

**3. The nudge is silenced two ways, and the two cases are distinguished.**
A real filter silences it (the predicate is no longer allow-all). An explicit
opt-out also silences it:

- core builder: `DefaultEntityResolver.Builder.allowUnfilteredPublicIds()`
- starter: `jclaim.public-id.filter: off`

The builder tracks an `acknowledgedUnfiltered` boolean rather than overloading
predicate identity, so *deliberate* allow-all ("I chose unfiltered") is
distinguishable from *default* allow-all ("I never noticed"). Only the latter
warns.

**Scope of this ADR** is the posture decision plus the nudge and its opt-out
(steps 1–3 and the Action Items). It does **not** build the denylist or the
`jclaim-filter` module (item 8) and does **not** add per-type filter keys
(item 9); it only fixes the default those follow-ups compose against.

## Options Considered

The headline axis is **where the default sits**, given core cannot carry
word-lists.

### Option A: Permissive default + core discoverability nudge (CHOSEN)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low — one WARN at build, one opt-out flag/key |
| Blast radius | `jclaim-core` resolver builder + starter config key; no behaviour change |
| Backward compatibility | Full — allow-all stays the default; mint unchanged |
| Core purity | Preserved — no reference data in core |

**Pros:** Keeps the grain (default == historic behaviour), keeps core pure, and
makes the genuinely consequential decision the *integrator's* — while refusing to
let it be silent. The nudge fires exactly once, in the right layer, and is
trivially silenceable by anyone who has actually made the call.
**Cons:** The default is not *safe*, only *loud*. An integrator who ignores logs
still ships unfiltered IDs. Accepted: the library cannot both keep core
list-free and be safe-by-default, and a loud permissive default is the honest
resolution.

### Option B: Permissive default, documentation only (no nudge)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Lowest — zero new code |
| Discoverability | Weak — risk stays genuinely silent |

**Pros:** Cleanest; zero noise; no new behaviour. **Cons:** The asymmetric,
un-recallable downside stays invisible to anyone who doesn't read the docs —
exactly the population most likely to ship an obscene person-ID. The marginal
cost of one WARN is low and the failure mode it guards is severe and permanent.
Rejected in favour of A.

### Option C: Safe-by-default via a minimal embedded baseline denylist in core

| Dimension | Assessment |
|-----------|------------|
| Complexity | Med — embed and maintain a list |
| Core purity | Violated — core carries reference data |

**Pros:** Genuinely safe out of the box, no module needed. **Cons:** Breaks the
"core carries no reference data" line ADR-0002 drew deliberately; an
English-leet-only list gives false confidence (it is *not* the multilingual
coverage the asymmetric risk warrants) while taking on list-maintenance in the
wrong module. Rejected.

### Option D: Safe-by-default conditional on the filter module's presence

| Dimension | Assessment |
|-----------|------------|
| Complexity | Med |
| Predictability | Poor — behaviour flips on classpath contents |

**Pros:** Safe for anyone who has opted into filtering at all. **Cons:** "Adding
a jar silently changes minting behaviour" is a surprising, hard-to-debug
implicit default; it couples the posture to packaging rather than to an explicit
choice. Rejected; explicit opt-in (Option A) expresses the same intent without
the spooky action.

## Trade-off Analysis

The constraint does the deciding. Core cannot carry the data a *safe* default
needs, so the only honest defaults are (a) permissive, or (b) a weak embedded
list that masquerades as safety. Given that, the real question is not "safe vs
permissive" — it is "how do we keep a permissive default from being *silent*."
The WARN answers exactly that: it converts an invisible, asymmetric, permanent
exposure into a visible one at the only moment the library has the integrator's
attention (resolver construction), without changing a single minted value and
without dragging reference data into core. The explicit opt-out keeps the signal
honest — it fires only when nobody has made the call, and goes quiet the instant
someone has, in either direction.

## Consequences

**Easier**
- Existing `0.1.0`/`0.2.0` display-ID users upgrade with zero behaviour change.
- The `jclaim-filter` module (item 8) and per-type filter keys (item 9) compose
  against a stable, documented default; turning filtering *on* is purely additive.

**Harder / to revisit**
- The default is loud, not safe: a team that ignores WARN logs can still ship
  unfiltered person-IDs. If field experience shows that is insufficient, a future
  ADR can revisit (e.g. a starter property that *requires* an explicit posture
  before minting person-typed IDs), but that is not warranted pre-evidence.
- One more builder slot + config key to document and keep coherent with the
  per-type keys that arrive in item 9 (`entity-types.<type>.public-id.filter`),
  where `off` must mean the same explicit acknowledgement per type.

**Neutral**
- `publicId` stays opt-in: a null generator (no template) mints no public ID and
  never warns.
- Uniqueness, alias atomicity, and stewardship-event behaviour are untouched.

## Action Items

**This change**
1. [x] `DefaultEntityResolver.Builder`: track `acknowledgedUnfiltered`; add
   `allowUnfilteredPublicIds()`.
2. [x] In `build()`, emit a single SLF4J WARN when a template is configured, the
   acceptance predicate is the allow-all default, and `acknowledgedUnfiltered`
   is false. Message points at docs, not the module name.
3. [x] Starter: map `jclaim.public-id.filter: off` to the builder
   acknowledgement; absent key + template ⇒ warns; a configured filter ⇒ silent.
4. [x] Tests: warns on default + template; silent on `allowUnfilteredPublicIds()`;
   silent with a real (non-allow-all) filter; silent when no template; warning
   emitted at most once per resolver.
5. [x] README / docs: document the permissive posture, the asymmetric
   un-recallable risk, and how to opt into filtering or acknowledge unfiltered.
6. [x] Tick ADR-0002 item 10 `[x]` with a pointer to this ADR.

**Out of scope (separate items — enabled by, not part of, this change)**
- Denylist acceptance filter + `jclaim-filter` sibling module — ADR-0002 item 8.
- Per-type filter/generator keys — ADR-0002 item 9.

## Notes

This ADR closes the last open question from ADR-0002. It follows the same grain:
the default is the historic behaviour, the consequential choice is the
integrator's, and the library's job is to make that choice *visible* rather than
to make it for them — while holding the line that reference data lives in a
sibling module, never in core.
