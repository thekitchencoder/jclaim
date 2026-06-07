# jclaim-storage-mongo

MongoDB storage adapter for [JCLAIM](../README.md). Implements the
`EntityStorage` port atop a single entities collection with a unique
compound index on `(aliases.source, aliases.sourceId)` — the atomic
primitive the port relies on.

## Maven dependency

```xml
<dependency>
    <groupId>uk.codery</groupId>
    <artifactId>jclaim-storage-mongo</artifactId>
    <version>0.2.0</version>
</dependency>
```

The module pulls `org.mongodb:mongodb-driver-sync` onto the runtime
classpath.

## Runtime requirements

- MongoDB 5+ (any deployment topology: single, replica set, sharded).
- A configured `MongoClient` from your application; the adapter takes a
  `MongoCollection<Document>` so the client lifecycle stays in your hands.

## Document shape

Each canonical entity lives as a single document in the configured
collection:

```json
{
  "_id": "urn:codery:entity:01931dad-d6c4-7c2e-bdfa-f49d33ed1ec3",
  "publicId": "K7M2-9X4P-N",
  "supersededBy": null,
  "createdAt": ISODate("2026-05-12T10:00:00Z"),
  "updatedAt": ISODate("2026-05-12T10:00:00Z"),
  "aliases": [
    { "source": "ecommerce", "sourceId": "cust-001" },
    { "source": "pos",       "sourceId": "loyalty-99" }
  ],
  "attributes": [
    { "name": "email", "value": "alice@example.com" },
    { "name": "phone", "value": "+44 7700 900110" }
  ]
}
```

The adapter creates the following indexes on construction:

| Index name                   | Keys                                                  | Unique | Notes                                |
|------------------------------|-------------------------------------------------------|--------|--------------------------------------|
| `jclaim_publicId_unique`     | `{ publicId: 1 }`                                     | yes    | Enforces public-ID uniqueness (partial: `$exists: true`). |
| `jclaim_aliases_unique`      | `{ "aliases.source": 1, "aliases.sourceId": 1 }`      | yes    | Enforces alias uniqueness across the collection — the storage port's atomic guarantee. |
| `jclaim_attributes_lookup`   | `{ "attributes.name": 1, "attributes.value": 1 }`     | no     | Supports `findCandidates(Claim)` per-attribute elemMatch lookups. Not part of any uniqueness contract; purely retrieval efficiency. |

All three indexes are idempotent (`createIndex` is a no-op if the index already
exists with matching options).

## Configuration

### With auto-created indexes (default)

```java
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import uk.codery.jclaim.storage.mongo.MongoEntityStorage;
import uk.codery.jclaim.resolver.DefaultEntityResolver;

MongoClient client = MongoClients.create("mongodb://localhost:27017");
var collection = client.getDatabase("jclaim").getCollection("entities");

var storage = MongoEntityStorage.create(collection);
var resolver = DefaultEntityResolver.builder(storage)
        .namespace("codery")
        .build();
```

### With externally managed indexes

```java
var storage = MongoEntityStorage.builder(collection)
        .createIndexes(false)     // indexes created by your migration tool
        .build();
```

## Known limitations

- **Attribute value round-trip uses the BSON codec.** Java `String`,
  `Integer`, `Long`, `Double`, `Boolean`, `Date`, `List`, and `Map` round-trip
  as themselves. Custom types require a registered codec on the
  `MongoClient`.
- **Single collection per adapter instance.** If you partition by entity
  type, you'd run one `MongoEntityStorage` per collection. This is by
  design — the namespacing knob is the collection name, not a per-row
  discriminator.
- **No transactions used.** The adapter relies on document-level atomicity
  from `insertOne` and `updateOne` with `$addToSet`, both backed by the
  unique compound index on aliases. Transactions are not required for the
  port's correctness contract.

## Testing

The Mongo adapter's tests use Testcontainers by default and a
pre-configured MongoDB when the following env var is set:

```bash
export JCLAIM_TEST_MONGO_URI='mongodb://localhost:27017'
mvn -pl jclaim-storage-mongo test
```

If neither Docker nor `JCLAIM_TEST_MONGO_URI` is available the
integration tests skip cleanly with a clear message.
