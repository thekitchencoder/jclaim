# jclaim-spring-boot-starter

Spring Boot 3.x auto-configuration for [JCLAIM](../README.md).

## What the starter does

- Auto-wires an `EntityResolver` bean (in-memory by default; opt-in
  Mongo or Postgres when the corresponding adapter and client are on
  the classpath).
- Selects the `MatchingPolicy`: `aliasOnly()` by default, or a
  jspec-backed `JspecMatchingPolicy` when `jclaim.matching.spec` points
  at a spec resource and `jclaim-matching-jspec` is on the classpath.
- Bridges `MatchEvent`s (`EntityAttributesConflicted`, `MatchUndecided`,
  `MatchAmbiguous`) to Spring's `ApplicationEventPublisher` as
  `JclaimMatchEvent`, so application code reacts via ordinary
  `@EventListener` methods.
- Registers optional Spring Boot Actuator `HealthIndicator` and
  Micrometer metrics (`MeterRegistry`-driven) when those facilities
  are on the classpath.

## Maven dependency

```xml
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jclaim-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

`jclaim-core` is pulled in transitively. Storage-adapter modules are
opt-in additional dependencies — add only the one you need.

## Quick Start — zero config

With just the starter on the classpath, a Spring Boot application
receives an in-memory `EntityResolver` bean immediately:

```java
@SpringBootApplication
public class App {

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}

@Component
class ReconciliationService {

    private final EntityResolver resolver;

    ReconciliationService(EntityResolver resolver) {
        this.resolver = resolver;
    }

    public ResolutionResult ingest(Claim claim) {
        return resolver.resolveOrMint(claim);
    }
}
```

No `application.yml` entries required. The resolver mints into a
`InMemoryEntityStorage` under the default `codery` URN namespace.

## Quick Start — MongoDB

Add the Mongo adapter and a `MongoClient` source (typically Spring
Boot's Mongo starter):

```xml
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jclaim-storage-mongo</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
```

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017
jclaim:
  storage:
    type: mongo
    mongo:
      database: jclaim
      collection-name: jclaim_entities
```

The starter resolves a `MongoClient` bean from the context, opens the
configured database + collection, and constructs a
`MongoEntityStorage`. Indexes are auto-created on startup.

## Quick Start — PostgreSQL

Add the Postgres adapter and a `DataSource` source (typically Spring
Boot's JDBC starter):

```xml
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jclaim-storage-postgres</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/jclaim
    username: jclaim
    password: jclaim
jclaim:
  storage:
    type: postgres
```

The starter picks up the `DataSource`, constructs a
`PostgresEntityStorage`, and applies the bundled `schema.sql` on
startup.

## Properties

All properties live under the `jclaim.*` prefix.

| Property                                  | Default            | Description                                                                                       |
|-------------------------------------------|--------------------|---------------------------------------------------------------------------------------------------|
| `jclaim.urn.namespace`                    | `codery`           | URN namespace; produces `urn:<ns>:<type>:<UUID>`.                                                 |
| `jclaim.urn.type`                         | `entity`           | URN type segment; produces `urn:<ns>:<type>:<UUID>`.                                              |
| `jclaim.human-id.template`                | _(none)_           | Human-id template; **absent → no humanId is minted**. Set a template to opt in (eagerly validated — a malformed template fails context startup). |
| `jclaim.storage.type`                     | `auto`             | One of `auto`, `in-memory`, `mongo`, `postgres`.                                                  |
| `jclaim.storage.mongo.database`           | `jclaim`           | Mongo database name.                                                                              |
| `jclaim.storage.mongo.collection-name`    | `jclaim_entities`  | Mongo collection name.                                                                            |
| `jclaim.storage.mongo.create-indexes`     | `true`             | Auto-create the unique alias + humanId indexes on startup.                                        |
| `jclaim.storage.postgres.apply-schema`    | `true`             | Auto-apply the bundled `schema.sql` on startup.                                                   |
| `jclaim.matching.spec`                    | _(none)_           | Classpath spec resource for a `JspecMatchingPolicy`; absent → `aliasOnly()`. Requires `jclaim-matching-jspec`. Eagerly validated — a missing resource fails context startup. |
| `jclaim.matching.max-candidates`          | `100`              | Cap on attribute-blocked candidates the policy scores per claim. Truncation → WARN + `jclaim.matching.pool_truncated_total`. |
| `jclaim.match-sink.type`                  | `spring-events`    | One of `spring-events`, `logging`, `noop`.                                                        |
| `jclaim.metrics.enabled`                  | `true`             | Wraps the resolver with a Micrometer-instrumented decorator when a `MeterRegistry` bean exists.   |
| `jclaim.health.enabled`                   | `true`             | Registers an Actuator `HealthIndicator` for the configured storage.                               |

### Entity type & namespace

The starter configures a **single entity type** — the kind of entity this
resolver reconciles, surfaced as the `<type>` segment of
`urn:<namespace>:<type>:<UUID>`. One resolver reconciles exactly one entity
type. The two URN coordinates play different roles:

- `jclaim.urn.namespace` — the organisation/tenant. A shared value for the
  whole application; every entity type lives under it. Default `codery`.
- `jclaim.urn.type` — the entity type itself (e.g. `customer`, `vehicle`).
  Default `entity` (generic/unclassified).

`jclaim.human-id.template`, `jclaim.matching.*` and `jclaim.storage.*`
describe the format, matching policy and storage that belong to this entity
type. The humanId itself is **opt-in** — minted only when
`jclaim.human-id.template` is set; with no template this entity type has no
humanId.

> **Roadmap.** These top-level keys define a single, default entity type.
> Reconciling **multiple entity types in one application** is planned: a
> `jclaim.entity-types.<type>` map where each key is a URN `<type>` segment
> overriding these same keys, with `jclaim.urn.namespace` /
> `jclaim.human-id.*` / `jclaim.matching.*` serving as the inherited defaults.
> The current keys are forward-compatible — the map is purely additive;
> nothing here changes when it lands. See
> `docs/plans/2026-06-04-multi-entity-type-direction.md`.

### humanId template

The humanId is **opt-in**: it is minted only when
`jclaim.human-id.template` is configured. With no template set, entities of
this type are minted with no humanId at all (no generation, no stored field,
no index entry). When you do set one, the property drives the shape of every
minted humanId. The template is compiled once into a `HumanIdFormat` at
startup and is eagerly validated — a malformed template fails context
startup.

Grammar (one template character → one output character):

- `?` is a **placeholder**. The **last `?`** renders the Damm check
  digit; every **other `?`** renders a random Crockford Base32 data
  symbol.
- Any other character is a **literal**, emitted verbatim.

Because the last placeholder is always the check digit, every
well-formed template yields a self-validating ID. There must be **2–13
`?` total** (1–12 data placeholders); the 12-data ceiling keeps the
value within a 60-bit `long`. A literal cannot itself be `?` (no
escaping in v1).

Examples (sample data `K7M2 9X4P`, check digit `3` — the Damm digit is
always 0–9, so the check character always renders as a digit):

| Template        | Breakdown                    | Renders         | Data bits |
|-----------------|------------------------------|-----------------|-----------|
| `????-????-?`   | 8 data + check               | `K7M2-9X4P-3`   | 40        |
| `#?????`        | literal `#` + 4 data + check | `#K7M23`        | 20        |
| `JG??????`      | `JG` + 5 data + check        | `JGK7M293`      | 25        |
| `ID????-????-?` | `ID` + 8 data + check        | `IDK7M2-9X4P-3` | 40        |

## Listening to match events

The default `MatchEventSink` republishes every `MatchEvent` as a
`JclaimMatchEvent` on the application context. The payload is the sealed
`MatchEvent` — pattern-match it to react per type:

```java
@Component
class StewardshipListener {

    @EventListener
    public void onMatchEvent(JclaimMatchEvent event) {
        switch (event.payload()) {
            case EntityAttributesConflicted c ->
                log.warn("conflict on {}: {}", c.stored().id(), c.differingValues());
            case MatchUndecided u ->
                log.info("minted {} with undetermined candidates", u.minted().id());
            case MatchAmbiguous a ->
                log.warn("ambiguous match; chose {}, {} runners-up",
                        a.winner().id(), a.otherMatched().size());
        }
    }
}
```

The stored entity is **not** silently updated — events preserve evidence
for stewardship. Note: a claim that only *adds* a new attribute is
additive, not a conflict; only differing values on shared attribute names
fire `EntityAttributesConflicted`.

## Overriding beans

Every bean the starter registers is `@ConditionalOnMissingBean`.
Define your own `EntityResolver`, `EntityStorage`, `MatchingPolicy`, or
`MatchEventSink` and the starter steps aside:

```java
@Configuration
class JclaimOverrides {

    @Bean
    MatchEventSink myMatchSink() {
        return event -> kafkaTemplate.send("jclaim.match-events", event);
    }
}
```

The same pattern works for `EntityStorage` (e.g. to plug in a custom
adapter) and for `EntityResolver` (e.g. to wrap the default with your
own decorator).

## Observability

### Health

When `spring-boot-starter-actuator` is on the classpath, a
`HealthIndicator` named `jclaimHealthIndicator` is registered. It
appears under `/actuator/health` and reports the configured storage
adapter's connectivity. Disable with `jclaim.health.enabled=false`.

### Metrics

When a `MeterRegistry` bean exists in the application context, the
resolver is wrapped with a Micrometer-instrumented decorator that
emits:

- `jclaim.resolve` — counter, tagged `outcome=matched|minted`.
- `jclaim.resolve.duration` — timer recording `resolveOrMint` latency.
- `jclaim.findCandidates` — counter incremented per `findCandidates`
  call.

Disable with `jclaim.metrics.enabled=false`.

## Storage selection priority

In `jclaim.storage.type=auto` (the default), the starter chooses an
adapter by scanning the application context. Current behaviour: when
both Postgres and Mongo adapters are present (both modules and both
client beans on the classpath), **Postgres wins** — this falls out of
the `@Import` ordering in the auto-configuration. To pin the choice
explicitly, set `jclaim.storage.type` to `mongo` or `postgres`. When
neither adapter is wired the starter falls back to
`InMemoryEntityStorage`.
