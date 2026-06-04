# Configurable Identity Formats — Design

**Status:** validated, ready for implementation plan
**Date:** 2026-06-04
**Scope:** one PR, `jclaim-core` + `jclaim-spring-boot-starter`

## Goal

Make two currently hard-coded aspects of entity identity configurable, both
**per-resolver** (consistent with how `namespace` already works):

1. The URN **type segment** — today the literal `entity` in
   `urn:<namespace>:entity:<uuid>`.
2. The **humanId format** — today the fixed `XXXX-XXXX-X` shape.

Nothing is published to Maven Central yet and the library is not in use
anywhere, so there are **no data or wire back-compat constraints**. Defaults
must reproduce today's output byte-for-byte so existing tests and corpora are
unaffected.

---

## Part A — Configurable URN type segment

### `EntityId` (jclaim-core/.../model/EntityId.java)

Currently `record EntityId(String urn)` with a two-group regex
(`namespace`, `uuid`) and the literal `:entity:` baked in.

Changes:

- Add `public static final String DEFAULT_TYPE = "entity";`
- Widen `URN_PATTERN` to capture the type as its own group:
  ```
  ^urn:([A-Za-z0-9][A-Za-z0-9-]*):([A-Za-z0-9][A-Za-z0-9-]*):(<uuid>)$
  ```
  Groups become **1=namespace, 2=type, 3=uuid**. `urn:codery:entity:<uuid>`
  still matches (the literal default is just a value the pattern now captures),
  so every existing URN string remains valid.
- New factory:
  ```java
  public static EntityId of(String namespace, String type, UUID uuid) {
      // null + blank checks on namespace AND type
      return new EntityId("urn:" + namespace + ":" + type + ":" + uuid);
  }
  ```
- Existing `of(namespace, uuid)` delegates with `DEFAULT_TYPE` →
  unchanged behaviour. `of(uuid)` unchanged (default namespace + type).
- Add `public String type()` (group 2). Update `namespace()`→group 1,
  `uuid()`→group 3.
- Type charset is validated by the same rule as namespace (alphanumeric +
  hyphen, leading alphanumeric, non-blank).

### Resolver threading (DefaultEntityResolver)

- `Builder.entityType(String)`, default `EntityId.DEFAULT_TYPE`.
- The mint site changes `EntityId.of(namespace, uuid)` →
  `EntityId.of(namespace, entityType, uuid)`.

### Spring threading

- `JclaimProperties`: introduce a nested `urn` group holding **both**
  `namespace` (default `"codery"`) and `type` (default `"entity"`). This moves
  the existing `jclaim.namespace` to **`jclaim.urn.namespace`** and adds
  **`jclaim.urn.type`** — a deliberate rename, safe because nothing is released.
- Auto-configuration passes both into the builder.
- README + any existing tests/fixtures referencing `jclaim.namespace` update to
  `jclaim.urn.namespace`.

---

## Part B — Templated humanId format

### Template grammar

A single template string subsumes length, grouping, separators, prefix and
suffix. Every template character maps to exactly **one** output character
(fixed width), which makes both formatting and validation a position-by-position
walk.

- `?` = **placeholder**. The **last `?`** renders the Damm check digit; every
  **other `?`** renders a random Crockford Base32 data symbol.
- any other character = **literal**, emitted verbatim.

Examples (sample data `K7M2 9X4P`, check digit `N`):

| Template          | Breakdown                       | Renders         | Data bits |
|-------------------|---------------------------------|-----------------|-----------|
| `????-????-?`     | 8 data + check (**default**)    | `K7M2-9X4P-N`   | 40        |
| `#?????`          | literal `#` + 4 data + check    | `#K7M2N`        | 20        |
| `JG??????`        | `JG` + 5 data + check           | `JGK7M29N`      | 25        |
| `ID????-????-?`   | `ID` + 4 data + check           | `IDK7M2-9X4P-N` | 40        |

Because the last placeholder is always the check digit, **every well-formed
template yields a self-validating ID** — there is no way to accidentally ship
one without typo protection.

### New value object — `HumanIdFormat` (jclaim-core/.../id/HumanIdFormat.java)

Immutable, compiled once from a template.

```java
public final class HumanIdFormat {
    public static final HumanIdFormat DEFAULT = ofTemplate("????-????-?");

    public static HumanIdFormat ofTemplate(String template);

    public int dataBits();
    public String format(long value);        // mask → encode data → Damm digit → walk plan
    public boolean isValid(String candidate);
}
```

**Construction / validation** (`ofTemplate`):
- Scan the template; collect placeholder positions and literal characters.
- `dataChars = placeholderCount - 1`; the last placeholder is the check slot.
- Require `placeholderCount >= 2` and `1 <= dataChars <= 12` (60-bit cap, the
  agreed ceiling that keeps the value in a `long`). Otherwise
  `IllegalArgumentException`.
- Precompute `dataBits = dataChars * 5`, the mask, and a compiled **plan**: one
  entry per output position — `LITERAL(char)`, `DATA`, or `CHECK`.
- No escaping in v1 → a literal cannot itself be `?` (documented; YAGNI).

**`format(long value)`**:
```
value &= mask
String data = CrockfordBase32.encode(value, dataBits)   // dataChars symbols
char checkChar = CrockfordBase32.ALPHABET.charAt(Damm.checkDigit(value))
walk plan: LITERAL→append char; DATA→append next data symbol; CHECK→append checkChar
```
The Damm digit is always 0–9 and `ALPHABET.charAt(0..9)` returns `'0'..'9'`
verbatim (the alphabet starts `0123456789`), so 0 and 1 render unambiguously.

**`isValid(String candidate)`** — structural + check-digit, alias-forgiving:
```
if candidate.length() != template length → false
walk plan against candidate:
  LITERAL(c): candidate char must equal c (exact)            else false
  DATA:       d = CrockfordBase32 DECODE[char] (forgives O→0, I/L→1)
              invalid → false; acc = (acc << 5) | d
  CHECK:      cd = DECODE[char]; invalid or > 9 → false; hold it
return Damm.verify(acc, heldCheck)
```
Data and check positions decode through the Crockford aliases, so a user who
types `O` for `0` or `I`/`L` for `1` — including in the check position —
validates correctly. Literals are matched exactly (case-sensitive). v1 is
fixed-width: hyphens/separators must be present (no blanket dash-stripping).

### `HumanIdGenerator` changes

Becomes format-aware rather than holding `DATA_BITS`/`DATA_CHARS` constants:

- Holds a `HumanIdFormat` (default `HumanIdFormat.DEFAULT`) and an entropy
  supplier sized to `format.dataBits()`.
- Constructors: `HumanIdGenerator(HumanIdFormat)`,
  `HumanIdGenerator(HumanIdFormat, Random)`,
  `HumanIdGenerator(HumanIdFormat, Supplier<Long>)`, and a no-arg →
  `DEFAULT`.
- `generate()` draws masked entropy and calls `format.format(value)`.
- Static `format(long)` / `isValid(String)` move onto `HumanIdFormat` (they are
  inherently format-specific now). Call sites and tests updated.

### Resolver threading

- `Builder.humanIdFormat(HumanIdFormat)` plus convenience
  `Builder.humanIdTemplate(String)` (→ `HumanIdFormat.ofTemplate`).
- The resolver builds its `HumanIdGenerator` from the configured format; the
  collision-remint path is unchanged (it just re-draws and re-formats).

### Spring threading

- `JclaimProperties`: add nested `humanId.template` (default `????-????-?`) →
  property **`jclaim.human-id.template`**.
- Auto-configuration calls `HumanIdFormat.ofTemplate(template)` and passes it to
  the builder. **Eager validation**: a malformed template fails context startup,
  matching how `jclaim.matching.spec` already behaves.
- README: document the property with the grammar table above.

---

## Cross-cutting constraints

- **Immutability** — `HumanIdFormat` is immutable; its plan is built once and
  never mutated.
- **Thread-safety** — format and generator carry no mutable shared state beyond
  the entropy supplier.
- **No Spring in core** — all new core types stay under `uk.codery.jclaim.*`.
- **Logging** — humanId-collision WARN on remint is retained.

## Testing

- **EntityId**: `of(ns,type,uuid)` renders `urn:ns:type:uuid`; omitting type
  yields `entity`; `namespace()`/`type()`/`uuid()` accessors; invalid/blank type
  rejected; existing `urn:codery:entity:<uuid>` strings still parse.
- **HumanIdFormat**:
  - `ofTemplate` validation — fewer than 2 placeholders, 0 data placeholders,
    and the 13-`?` (12-data) ceiling all throw.
  - `DEFAULT` reproduces `XXXX-XXXX-X` exactly (golden test against current
    `HumanIdGenerator.format`).
  - prefix/suffix templates render as the grammar table specifies.
  - `format` golden cases for several templates.
  - `isValid`: happy path; alias forgiveness (`O`/`0`, `I`/`L`/`1`) in both data
    and **check** positions; wrong check digit; literal mismatch; length
    mismatch.
  - **Damm digit 0 and 1 render as `0`/`1`** — explicit regression test for the
    case we flagged.
- **Resolver**: minting honours a configured `entityType` and humanId template;
  collision-remint still produces a valid, unique ID.
- **Spring**: `jclaim.entity-type` and `jclaim.human-id.template` bind; a bad
  template fails startup eagerly; defaults reproduce current behaviour end-to-end.
- Existing corpus + storage-contract tests must stay green on defaults.

## New properties (summary)

| Property                     | Default        | Effect                                     |
|------------------------------|----------------|--------------------------------------------|
| `jclaim.urn.namespace`       | `codery`       | URN namespace (renamed from `jclaim.namespace`) |
| `jclaim.urn.type`            | `entity`       | URN type segment: `urn:<ns>:<type>:<uuid>` |
| `jclaim.human-id.template`   | `????-????-?`  | humanId template (grammar above)           |

## Out of scope (YAGNI)

- Per-claim / per-entity URN types (per-resolver only).
- humanIds beyond 60 bits (would require `BigInteger` instead of `long`).
- Dash-insensitive or case-insensitive-literal humanId parsing.
- Template escaping for a literal `?`.
