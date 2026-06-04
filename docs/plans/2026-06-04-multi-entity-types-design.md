# Multiple Entity Types — Design

**Status:** design agreed (brainstormed 2026-06-04); ready for an
implementation plan.
**Scope:** end-to-end, all storage backends — core multi-resolver
readiness, the `jclaim.entity-types` starter config, per-type storage
isolation across in-memory / Mongo / Postgres, and the selection API.
**Supersedes the open questions in:**
[`2026-06-04-multi-entity-type-direction.md`](2026-06-04-multi-entity-type-direction.md)
(which settled the config *shape*; this doc resolves the deferred
load-bearing decisions).

## Decisions at a glance

| Question | Decision |
|---|---|
| Milestone scope | End-to-end, all stores |
| Alias isolation | **Physical per-type** (port frozen) |
| Postgres mechanism | **Schema-per-type**, configurable, default = current behaviour |
| Selection API | **Both** — per-type qualified beans + `EntityResolvers` facade |
| Storage scoping | Auto from type key, shared connection, **per-type own-connection escape hatch** |
| Per-type observability | **In scope** — `type` metric tag + per-type health |

## 1. Config model & modes

Two modes, selected by the **presence of `jclaim.entity-types`**:

- **Single-type mode** (no `entity-types` key) — exactly today's
  behaviour. Top-level `jclaim.urn.{namespace,type}`, `human-id`,
  `matching`, `match-sink`, `storage` define one resolver, published as
  the `jclaimResolver` bean. Byte-for-byte unchanged. No facade, no
  qualified beans.
- **Multi-type mode** (`entity-types` present) — the top-level keys
  become **inherited defaults only**, never a standalone resolver. Each
  `entity-types.<type>` entry mints one resolver. No unnamed default
  resolver is published in this mode — *pick one mode, don't mix*. The
  map key is simultaneously the URN `<type>` segment, the bean
  `@Qualifier`, the `EntityResolvers.forType(...)` handle, and the
  default storage scope name.

### Inheritance rules

**Inherited from top-level (overridable per entry):**

- `urn.namespace` — the tenant; the one genuinely-shared identity field.
- `matching.max-candidates` — a numeric tuning knob; a shared default
  is sensible.

**Per-type only — no top-level default carries down:**

- `urn.type` — *is* the map key. Never inherited.
- `human-id.template` — opt-in per type. Absent on an entry → that type
  mints `humanId == null`. No global template (a shared template would
  silently stamp every type with same-shaped IDs, defeating the opt-in).
- `matching.spec` — each type matches by its own rules; a shared spec
  across e.g. customer/vehicle is meaningless.

**Application-global (single value, not per-type):**

- `match-sink.*` — one sink for the whole app.
- storage backend kind (in-memory / mongo / postgres) + the shared
  connection. Only the *scope* (schema/collection) and an optional
  own-connection override are per-type.

In multi-type mode the top-level `urn.type` and `human-id.template` are
simply unused (they are the single-type block); only `namespace`,
`matching.max-candidates`, `match-sink`, and the storage substrate carry
down.

### Example

```yaml
jclaim:
  urn:
    namespace: acme            # tenant — inherited by every type
  matching:
    max-candidates: 100        # inherited default
  match-sink:
    type: spring-events        # app-global
  storage:
    type: postgres             # backend kind — app-global

  entity-types:
    customer:                  # → urn:acme:customer:<uuid>, schema "customer"
      human-id: { template: "CU-????-????-?" }
      matching: { spec: matching/customer.yaml }
    vehicle:                   # → urn:acme:vehicle:<uuid>, schema "vehicle"
      human-id: { template: "VH??????" }
      storage: { datasource: vehicleDataSource }   # own connection
```

## 2. Core changes (minimal, Spring-free)

A multi-type setup is N `DefaultEntityResolver` builders; the builder
already carries every per-type knob (namespace, entityType,
humanIdTemplate, matchingPolicy, storage, maxCandidates,
matchEventSink). **No builder API change.**

Two additions:

1. **`EntityResolvers` registry** (new, `uk.codery.jclaim.resolver`) — a
   Spring-free immutable holder over `Map<String, EntityResolver>` keyed
   by type segment. The *core* home of the facade; the starter wraps the
   beans it builds rather than inventing a starter-only type. Surface:
   - `EntityResolver forType(String type)` — throws
     `IllegalArgumentException` on unknown type, message listing known
     types.
   - `Optional<EntityResolver> find(String type)` — non-throwing.
   - `Set<String> types()` — iterate all.
   - Built from a `Map` (defensive copy; reject blank keys).

   Keeps multi-type usable and testable without Spring (embed-first).
   Named `EntityResolvers` to match core's `EntityResolver` /
   `EntityStorage` vocabulary — so the starter injection type is
   `EntityResolvers`, not a starter-only `JclaimResolvers`.

2. **Eager URN-segment validation at `build()`** — today a bad
   `namespace`/`type` fails at first mint. Move it to
   `DefaultEntityResolver.Builder.build()` (delegating to `EntityId`'s
   segment rules; add them there if missing), consistent with the
   eager humanId-template validation.

Core stays free of any "scope" concept — isolation is achieved by
handing each resolver a separately-scoped adapter instance, purely an
adapter + starter concern.

## 3. Storage isolation mechanics

Port stays **frozen**. Isolation = N separately-scoped adapter instances
over (usually) one shared connection.

- **In-memory** — zero adapter change. One `InMemoryEntityStorage` per
  type; its `ConcurrentHashMap`s are the boundary.
- **Mongo** — zero adapter code change. Collection name is already a
  construction param. One `MongoEntityStorage` per type, collection
  defaulting to the type key (overridable). Indexes are created
  per-collection already, so the partial-unique humanId index and the
  `(aliases.source, aliases.sourceId)` unique index are naturally
  per-type — two types can hold the same alias or humanId without
  collision.
- **Postgres** — the one real change. Add `.schema(String)` to the
  builder (null/blank = today's behaviour, the default, so the
  single-type path is unchanged).
  - *Provisioning:* when `applySchema` is on and a schema is set, run
    `CREATE SCHEMA IF NOT EXISTS "<schema>"`, then apply `schema.sql`
    with `search_path` pointed at that schema so the
    `CREATE TABLE IF NOT EXISTS` land inside it.
  - *Runtime:* **prefer schema-qualified SQL** — resolve the schema once,
    validate it as a SQL identifier, quote it, and qualify table
    references (`"<schema>".entities`, …). Query *semantics* stay
    unchanged (the constraint-guarded INSERT logic is untouched); only
    the table references are templated, at ~12 sites. This avoids
    `search_path` leakage on pooled connections entirely. If
    `search_path` is used instead it must be made pool-safe — see
    **Addendum C** for the `SET LOCAL` / rollback trap that makes the
    naïve version an isolation bug. Index, FK, and PK names are
    schema-scoped automatically, so no cross-type name collisions.

**Per-type own connection** (escape hatch): a type may bind its own
`DataSource` / `MongoClient`; that resolver's adapter is built against
it. Schema/collection still default to the type key. Pure physical
separation when needed.

## 4. Starter wiring & selection API

**Properties.** Add `Map<String,EntityType> entityTypes` to
`JclaimProperties`, where `EntityType` mirrors the overridable subset:
`urn` (namespace override only — type comes from the key), `human-id`
(template), `matching` (spec + max-candidates), `storage` (scope-name
override + optional `datasource`/`mongo-client` bean-name). `match-sink`,
storage *kind*, and the shared connection stay top-level.

**Dynamic bean registration.** Bean names are data-driven, so a
`BeanDefinitionRegistryPostProcessor` (`EntityTypeResolverRegistrar`)
binds `jclaim.entity-types` via `Binder` and, per type, registers:

- one `EntityResolver` bean, **bean name = the type key**,
  `@Qualifier`-able by that name, with a supplier that builds the
  resolver lazily: scoped storage adapter (Postgres `.schema(type)` /
  Mongo collection=type / in-memory) over the shared or
  per-type-overridden connection, the type's matching policy
  (`spec`→`JspecMatchingPolicy`, else `aliasOnly()`), the type's
  `humanIdTemplate`, inherited `namespace` + `max-candidates`, and the
  app-global `MatchEventSink`.

Then one **`EntityResolvers` facade bean** (the core registry)
aggregating them → `forType(...)`, `types()`, `find(...)`.

**Mode exclusivity.** A shared `@Conditional` keys off the presence of
`jclaim.entity-types`:

- absent → today's single `jclaimResolver` `@Bean` (unchanged).
- present → the registrar runs; the single-type bean is suppressed. No
  mixing.

**Selection API:**

```java
@Qualifier("customer") EntityResolver customers;   // static, compile-wired
@Autowired EntityResolvers jclaim;                 // dynamic
jclaim.forType("vehicle");  jclaim.types();
```

**Observability** (Actuator health + Micrometer already exist): in
multi-type mode add a `type` tag to the metrics and a per-type health
contribution, so each resolver is independently observable rather than
reporting one blended number.

## 5. Testing strategy

- **Core:** `EntityResolvers` unit tests (`forType` unknown throws
  listing known types; `find`; `types`; blank/duplicate-key guards).
  Builder eager-validation tests (malformed namespace/type fails at
  `build()`).
- **Storage isolation (per adapter):** two scoped instances over **one**
  connection — same `(source, sourceId)` resolves to independent
  entities; humanId minted under type A invisible to type B. Postgres
  adds a schema-provisioning test; Mongo a collection-per-type test. The
  existing `EntityStorageContract` runs **unchanged** per instance.
- **Starter (multi-type integration):** context loads with N qualified
  beans + the `EntityResolvers` facade; single-type `jclaimResolver`
  bean **absent** in multi mode, **present** in single mode (mode
  exclusivity). Inheritance assertions: `namespace` and `max-candidates`
  inherited; `type`, `human-id.template`, `matching.spec` **not**
  inherited. Per-type own-`DataSource` wiring test. Observability:
  metrics carry the `type` tag; per-type health contribution present.

## 6. Validation & error handling (fail fast at startup)

- **Malformed URN segment** (type key, or overridden namespace) →
  context startup failure naming the offending value.
- **Blank/empty type key** → rejected at bind.
- **Scope collision** — two types resolving to the same schema/collection
  on the same connection → startup failure (would silently merge their
  data otherwise; the dangerous case, so detect it explicitly).
- **Invalid scope identifier** — a Postgres schema or Mongo collection
  name that is not a safe identifier → startup failure. Also closes the
  injection vector opened by templating qualified SQL (see §3 / Addendum
  C).
- **`matching.spec` set but `jclaim-matching-jspec` absent** → the
  existing actionable fail-fast message, now per type.
- **Per-type `datasource`/`mongo-client` bean missing** → clear startup
  error naming the type and the missing bean.
- **`forType(unknown)`** at runtime → `IllegalArgumentException` listing
  known types.

## Out of scope (still deferred)

- **Logical shared store** — folding `type` into the alias key
  (`(type, source, sourceId)`) in one shared store. Physical isolation
  is chosen this milestone; revisit logical only on concrete demand
  (it's additive — it changes the port + all adapters + the contract,
  so it stays a separate, deliberate decision).
- **Merge / split operations** — unchanged from the roadmap.

## Addendum — implementation adjustments

These points do not change the headline design: multi-type support is
still N resolvers over N physically-scoped storage adapters, with the
core storage port unchanged. They tighten the implementation so the
design is safer in real applications.

### A. Resolver type safety

`DefaultEntityResolver` already carries the configured URN type segment.
Use that knowledge at the resolver boundary:

- Apply the guard at **every URN-accepting entry point**. Confirm first
  which exist: today `resolveOrMint(Claim)` is the resolver's only
  operation, so `getByUrn` / `addAlias` may be *new* public API rather
  than existing methods to harden — name that as scope in the plan.
- Reject a URN whose `type()` **or `namespace()`** differs from the
  resolver's configured values — a right-type / foreign-namespace URN is
  equally the wrong resolver.
- The failure should be explicit, e.g. `IllegalArgumentException` naming
  the resolver's `(namespace, type)` and the received URN's.

Without this, a customer resolver asked about a vehicle URN will usually
look like a normal "not found" because storage is physically isolated.
That hides a caller bug. A wrong-type URN is not a missing entity; it is
the wrong resolver.

This is still a core-only invariant and does not add type awareness to
`EntityStorage`.

### B. Spring bean names vs qualifiers

Do not use the raw entity type key as the Spring bean name. Keys such as
`customer`, `vehicle`, or `order` are plausible application bean names,
so registering resolvers under those exact names creates avoidable
collisions.

Register resolver bean names with a JClaim-specific prefix, for example:

```text
jclaimEntityResolver_customer
jclaimEntityResolver_vehicle
```

Still attach the user-facing qualifier using the type key:

```java
@Qualifier("customer") EntityResolver customers;
@Autowired EntityResolvers jclaim;
```

The static injection API therefore remains domain-shaped, while the bean
registry stays namespaced. `EntityResolvers` should also be built from
the same type-key map, not from prefixed bean names.

**Implementation note.** `@Qualifier("customer")` only resolves to a
bean whose registered *name* differs (`jclaimEntityResolver_customer`)
if the registrar attaches qualifier metadata to the definition
explicitly:
`beanDefinition.addQualifier(new AutowireCandidateQualifier(Qualifier.class, "customer"))`.
Without it the domain-shaped injection silently fails to match.

### C. Postgres schema scoping rules

Postgres is the highest-risk part of physical isolation because the
current adapter intentionally uses unqualified table names. Schema
scoping must be made connection-pool safe.

Acceptable implementation approaches:

1. **Prefer schema-qualified SQL** where practical. Resolve the schema
   once, validate it as an identifier, quote it safely, and qualify table
   references (`"<schema>".entities`, etc.). This avoids `search_path`
   leakage altogether.
2. If using `search_path`, centralise all connection borrowing behind a
   helper that scopes the connection immediately and reliably restores or
   neutralises the scope before returning it to the pool.

If `search_path` is used, transactional paths need particular care:

- `SET LOCAL search_path ...` only lasts for the current transaction.
- A `rollback()` clears that local setting.
- Any post-rollback reads, such as unique-violation handling, must
  re-apply the schema scope before querying.
- Non-transactional paths must not leave a permanent `search_path` on a
  pooled connection.

The implementation plan should include tests that prove resolver A cannot
see resolver B's rows after interleaved calls on the same `DataSource`.

### D. Human ID uniqueness semantics

With physical isolation, `humanId` uniqueness is per storage scope, not
global across all entity types. That is consistent with the design, but
it must be documented explicitly:

- `findByHumanId(...)` is resolver/type-scoped.
- Two entity types may legally mint the same human ID if their templates
  and entropy collide.
- Examples should prefer type-specific templates (`CU-...`, `VH...`)
  when the IDs may be spoken, displayed, or searched outside a
  type-specific screen.

This keeps the default physical-isolation model honest and avoids
implying a global human-ID namespace that the storage design does not
provide.

### E. Multi-type metrics and health wiring

The current starter metrics model decorates the single `jclaimResolver`.
In multi-type mode that bean is intentionally absent, so metrics and
health need a separate path:

- decorate or instrument each typed resolver independently;
- add `type=<entity-type>` to resolver metrics;
- avoid a single `@Primary EntityResolver` in multi-type mode;
- expose one health contribution per storage scope/type.

The `MatchEventSink` can remain application-global, but event metrics
should include a type tag only if the event itself or the resolver wrapper
can supply the type without parsing URNs as a hidden dependency.

### F. Scope collision detection limits

Startup should reject two type entries that resolve to the same
schema/collection on the same configured connection, because that would
silently merge their data.

Use bean identity plus resolved scope name as the practical check:

- same `DataSource` bean + same Postgres schema → reject;
- same `MongoClient` bean + same database + same collection → reject;
- same in-memory storage instance should never be shared by generated
  multi-type wiring.

Document the limitation: if an application defines two distinct connection
beans that wrap the same physical database, the starter cannot reliably
prove they are the same store. The explicit own-connection escape hatch
therefore carries responsibility for choosing distinct scopes.

### G. Suggested implementation order

1. Core: `EntityResolvers`, eager URN segment validation, wrong-type URN
   guards.
2. Storage isolation tests for in-memory and Mongo, proving duplicate
   aliases/human IDs are independent across type scopes.
3. Postgres schema support and pool-safe scoping, with same-`DataSource`
   isolation tests.
4. Starter properties and strict single-type vs multi-type mode
   conditionality.
5. Dynamic resolver registration using prefixed bean names plus
   type-key qualifiers.
6. `EntityResolvers` facade bean aggregation.
7. Per-type metrics, health, and failure-mode tests.
8. README/starter docs explaining resolver selection and per-type human
   ID uniqueness.

Steps 3, 5, and 7 are implementation steps and stay **test-first within
themselves** — the two dedicated test steps (2) do not absolve the rest
of TDD.

### H. Plan-level wiring notes

1. **Lazy connection resolution in the registrar.** A
   `BeanDefinitionRegistryPostProcessor` runs *before* bean
   instantiation, so a per-type `storage.datasource` /
   `storage.mongo-client` bean **cannot be resolved during
   registration**. The registered bean definition's supplier must look
   the connection up lazily from the `BeanFactory` at creation time.
   Resolving it eagerly in the post-processor compiles but fails
   confusingly at runtime (or forces premature bean instantiation).
2. **Single-type regression lock.** An explicit test that the
   no-`entity-types` path is unchanged — bean name `jclaimResolver`,
   same metrics/health shape — so the multi-type machinery can never
   silently alter today's single-type behaviour.
3. **Per-type health contributor names** need the same prefixing
   discipline as bean names (Addendum B applies to contributor IDs too),
   to avoid colliding with application-defined health contributors.

### E (clarification). Type tagging is structured access, not URN parsing

Recovering an event's type via `entity.id().type()` — a structured
accessor on `EntityId` — is legitimate and is **not** the "hidden URN
parsing" dependency to avoid; string-splitting a URN would be. Since
each typed resolver wrapper already knows its own configured type, it can
tag events directly before they reach the application-global
`MatchEventSink`, so per-type event metrics are achievable cleanly.
