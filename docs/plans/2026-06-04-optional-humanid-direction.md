# Optional humanId — Direction (captured decision)

**Status:** direction agreed; not scheduled. Its own PR, **after** the
configurable-identity-formats PR (#6) merges.

## Decision

humanId becomes **opt-in, driven by the presence of a template** — no separate
`enabled` flag. If an entity type provides a humanId template it mints humanIds
in that format; if it provides none it mints **no humanId** — no generation, no
stored field, no index entry.

The URN is the identity; the humanId is a secondary, human-typed *lookup
handle*. Many entity types are machine-keyed (audit events, line items, ledger
entries) and never need one — minting it always wastes CPU (generation + Damm
check), storage (a field per record), and index space. Nothing is released, so
there is no back-compat pull: the default is simply the best one — **off unless
a template is given**. A global default humanId makes even less sense once an
application hosts multiple entity types, each deciding for itself.

## API

- **Builder** collapses to a single `humanIdTemplate(String)` knob, defaulting
  to **null = no humanId**. Drops the `enabled` flag and the public
  `humanIdFormat(...)` / `humanIdGenerator(...)` overloads from the common
  surface (an internal/advanced entropy hook stays for deterministic tests).
- **Config** is presence-driven: `jclaim.human-id.template` set → mint; absent
  → none. Per entity type in the future map:
  `customer: { human-id: { template: "CU-????-????-?" } }` has them;
  `audit-event: {}` does not.
- **Model:** `Entity.humanId` becomes genuinely nullable.

## Storage — the substance (not a config tweak)

Allowing an absent humanId forces the adapters to accept null without a false
collision. There is no config-only version of this; the optionality is realised
in storage:

- **Mongo** — a **partial** unique index
  (`partialFilterExpression: { humanId: { $exists: true } }`), and the mapper
  **omits the field entirely** when absent. (A plain *sparse* index still
  indexes a present-but-`null` value, so every humanId-less doc would collide on
  null — the field must be missing, not null.)
- **Postgres** — nullable `human_id` column + a **partial unique index**
  `... WHERE human_id IS NOT NULL` (keeps nulls out of the index, not just
  tolerated by SQL's multi-null rule).
- **In-memory** — skip null keys in the humanId map.
- **`EntityStorageContract`** — cases proving humanId-less entities store,
  round-trip as null, coexist without a null-collision, and that a real humanId
  collision among entities that *do* have one still re-mints.

The dedup-on-mint guard is unchanged in spirit — the unique index is still the
collision check; making it partial just scopes it to "unique among entities
that have a humanId."

## Relationship to PR #6

PR #6 ships humanId **always-on** with a default template `????-????-?`. This
feature **flips that default** to presence-driven (off unless a template is
set) and does the storage work. The existing tests/corpora that assume a
humanId is present are updated here — each either declares a template or accepts
null. That churn, the nullable model, and the partial-index work are why this is
its own PR rather than a retrofit into #6.

## Sequence

After #6 merges. **Independent** of the multiple-entity-types milestone — it
works for the single default resolver too — though the per-type template
naturally lives in the `jclaim.entity-types.<type>` map once that lands. Mostly
a storage-led change; smaller than #6.
