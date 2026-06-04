# Multiple Entity Types — Config Direction (captured decision)

**Status:** direction agreed; not scheduled. Recorded so the
configurable-identity-formats PR's config stays forward-compatible.

## Decision

jclaim is embed-first ("the MDM pattern as a library, not a platform"). In the
embedding case the URN **namespace** is the organisation/tenant — one value for
the whole application — and what varies is the **entity type** (customer,
vehicle, order):

- **namespace** — a shared, defaulted field (`jclaim.urn.namespace`), inherited
  by every entity type.
- **type** — the identity of an entity type; one resolver reconciles exactly
  one entity type.

"Resolver" stays an implementation detail (the `EntityResolver` bean the
library builds *for* each entity type, injected via `@Qualifier("<type>")`). It
must not appear in the config vocabulary — users declare **entity types**, not
resolvers.

## Config shape — `entity-types` keyed map (chosen)

Multiple entity types are a **flat keyed map** under `jclaim.entity-types`,
additive to today's keys. Each key is a URN `<type>` segment:

    jclaim:
      urn:
        namespace: acme          # the tenant — the <namespace> in every URN
      human-id:
        template: "????-????-?"  # shared default
      matching:
        max-candidates: 100      # shared default
      match-sink:
        type: spring-events      # app-global

      entity-types:              # each key is a URN <type> segment
        customer:                # → urn:acme:customer:<uuid>
          human-id: { template: "CU-????-????-?" }
          matching: { spec: matching/customer.yaml }
        vehicle:                 # → urn:acme:vehicle:<uuid>
          human-id: { template: "VH??????" }

Reads as plain domain language: *"under tenant `acme`, I reconcile `customer`
and `vehicle` entity types, each with its own ID format and matching rules."*

- The map **key is the URN `<type>` segment** and the bean qualifier /
  lookup handle — so `customer` → `urn:acme:customer:<uuid>` and
  `@Qualifier("customer") EntityResolver`.
- An entry's sub-keys (`urn`, `human-id`, `matching`, `storage`) mirror the
  top-level keys; the top-level values are the inherited defaults. An entry may
  override `urn.namespace` if it must live under a different tenant.
- Absent/empty `entity-types` == one default entity type defined by the
  top-level `jclaim.urn.{namespace,type}` == current behaviour. When
  `entity-types` is present, top-level `jclaim.urn.type` is the default/fallback
  type for the unnamed entry (pick one mode; don't mix needlessly).

### Naming

- **`entity-types`**, not `resolvers` — "resolver" leaks the implementation;
  users declare the kinds of entity they reconcile.
- **`entity-types`**, not `entities` — jclaim already uses **Entity** for the
  reconciled *record* (an instance); `entity-types` is unambiguous: the key is
  the type, not a record.

**Rejected shapes:** namespace-grouped two-level map
(`jclaim.urns.<ns>.<type>`) and namespace/entity lists. The flat keyed map
gives unique human-chosen handles, decouples the handle from the URN
coordinates, and keeps override ergonomics clean
(`jclaim.entity-types.customer.human-id.template=…` vs. fragile list indices).
Grouping by namespace wins only if jclaim becomes a multi-namespace
identity-authority *service* rather than an embedded library — revisit then;
it's additive and non-breaking.

## Core readiness

Little core change is expected: a multi-type setup is N `DefaultEntityResolver`
builders, and the resolver already takes namespace / type / humanId format /
matching / storage per instance. The starter's resolver bean is already named
`jclaimResolver` (not `@Primary`) precisely so named per-type resolvers can be
wired alongside it.

## Deferred (not decided)

- **Storage isolation** — the load-bearing decision. Aliases are unique on
  `(source, sourceId)` per store, so two entity types sharing an alias would
  collide; aliases must be scoped per type. Two ways:
  - **Physical** — collection-per-type (Mongo) / schema-per-type (Postgres).
    Near-zero change to the `EntityStorage` port + its atomicity contract; the
    conformance suite stays valid per store. Postgres needs its currently-fixed
    table/schema names made per-entity-type.
  - **Logical** — one shared store with `type` folded into the alias key
    (`(type, source, sourceId)`). Single store, but changes the port + all
    three adapters + the contract suite — the most safety-critical code.
- **Selection/registry API** — qualified beans vs. a `JclaimResolvers` facade
  (`forType("customer")`). Likely both.
- **Eager validation** of `jclaim.urn.type` / `namespace` at startup (today
  they fail at first mint, unlike the template which is eager).

## Out of scope for the current PR

No `jclaim.entity-types` key ships until it does something — a config surface
with no implementation is worse than none. This document only records the
direction so the shipped single-type config remains the forward-compatible
defaults layer.
