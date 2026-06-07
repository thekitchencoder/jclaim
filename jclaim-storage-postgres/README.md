# jclaim-storage-postgres

PostgreSQL storage adapter for [JCLAIM](../README.md). Implements the
`EntityStorage` port using plain JDBC against a normalised three-table
schema; alias uniqueness across the database is enforced by a single primary
key on `entity_aliases (source, source_id)`, which is the atomic primitive
the port relies on.

## Maven dependency

```xml
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jclaim-storage-postgres</artifactId>
    <version>0.2.0</version>
</dependency>
```

The module pulls `org.postgresql:postgresql` (JDBC driver) and
`com.fasterxml.jackson.core:jackson-databind` (jsonb attribute
serialisation) onto the runtime classpath. No Spring, no JPA, no JDBI —
plain JDBC by design.

## Runtime requirements

- PostgreSQL 13+ (`ON CONFLICT (...) DO NOTHING`, `jsonb`).
- A pooled `DataSource` is recommended in production; the adapter takes any
  `javax.sql.DataSource` so HikariCP, c3p0, or your platform's default
  pool all work without change.

## Schema

The adapter applies the following schema on construction (idempotent via
`CREATE TABLE IF NOT EXISTS`):

```sql
CREATE TABLE entities (
    urn            text PRIMARY KEY,
    public_id      text NULL,
    superseded_by  text NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);
CREATE UNIQUE INDEX entities_public_id_unique ON entities (public_id) WHERE public_id IS NOT NULL;

CREATE TABLE entity_aliases (
    source       text NOT NULL,
    source_id    text NOT NULL,
    entity_urn   text NOT NULL REFERENCES entities (urn) ON DELETE CASCADE,
    attached_at  timestamptz NOT NULL,
    position     int NOT NULL,
    PRIMARY KEY (source, source_id)
);
CREATE INDEX idx_entity_aliases_entity_urn ON entity_aliases (entity_urn);

CREATE TABLE entity_attributes (
    entity_urn  text NOT NULL REFERENCES entities (urn) ON DELETE CASCADE,
    name        text NOT NULL,
    value       jsonb NOT NULL,
    position    int NOT NULL,
    PRIMARY KEY (entity_urn, name)
);
CREATE INDEX idx_entity_attributes_entity_urn ON entity_attributes (entity_urn);
-- Supports findCandidates' per-attribute (name, value) lookup.
-- Not part of any uniqueness contract; purely retrieval efficiency.
CREATE INDEX idx_entity_attributes_name_value ON entity_attributes (name, value);
```

The source of truth is
[`schema.sql`](src/main/resources/uk/codery/jclaim/storage/postgres/schema.sql)
on the classpath. Auto-application is opt-out for environments where DDL is
owned externally (Flyway, Liquibase, your platform's migration tool).

## Configuration

### With auto-applied schema (default)

```java
import uk.codery.jclaim.storage.postgres.PostgresEntityStorage;
import uk.codery.jclaim.resolver.DefaultEntityResolver;

DataSource ds = /* your pooled DataSource */;
var storage = PostgresEntityStorage.create(ds);
var resolver = DefaultEntityResolver.builder(storage)
        .namespace("codery")
        .build();
```

### With externally managed schema

```java
var storage = PostgresEntityStorage.builder(ds)
        .applySchema(false)        // DDL applied by Flyway / Liquibase / DBA
        .build();
```

### Custom Jackson ObjectMapper

`MatchingAttribute.value` is `Object`; the adapter serialises values to
jsonb via Jackson. Override the mapper if you carry custom types:

```java
var storage = PostgresEntityStorage.builder(ds)
        .objectMapper(myCustomMapper)
        .build();
```

## Known limitations

- **Attribute value round-trip is JSON-typed.** Java `Integer`, `Long`,
  `Double`, `Boolean`, `String`, `List`, and `Map` round-trip as
  themselves. `BigDecimal` and `Float` collapse to `Double` because JSON
  has no separate number types. If exact-equality matters for non-JSON
  scalar types, normalise on the way in.
- **No connection pool included.** The adapter accepts any `DataSource`
  but does not pool internally. Wrap with HikariCP or your platform's
  pool for production.
- **Read-your-write transactional semantics only.** The adapter relies on
  per-statement atomicity from PostgreSQL's `INSERT ... ON CONFLICT`
  primitives; it does not start long-running transactions and is not
  suitable for read-modify-write patterns across multiple ports.
- **No support for `SERIALIZABLE` isolation gymnastics.** The port's race
  semantics are documented as: if two threads call `resolveOrCreate` on
  the same alias, exactly one mints and the other observes `Existing`.
  This is satisfied by the unique constraint on `(source, source_id)` —
  no isolation tuning required.

## Testing

The Postgres adapter's tests use Testcontainers by default and a
pre-configured database when the following env vars are set:

```bash
export JCLAIM_TEST_POSTGRES_JDBC_URL='jdbc:postgresql://localhost:5432/jclaim'
export JCLAIM_TEST_POSTGRES_USER=jclaim
export JCLAIM_TEST_POSTGRES_PASSWORD=jclaim
mvn -pl jclaim-storage-postgres test
```

If neither Docker nor `JCLAIM_TEST_POSTGRES_JDBC_URL` is available the
integration tests skip cleanly with a clear message.
