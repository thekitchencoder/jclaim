# Examples

End-to-end runnable demonstrations of JClaim against the synthetic
corpora used by the integration tests. Each demo loads a curated slice
of one corpus, folds each ground-truth entity's source-system records
into the resolver one alias at a time, and prints the resulting entity
graph.

All example classes live on the `jclaim-core` test classpath (the
fixture loaders are test-scope utilities under that module). From the
project root:

```bash
mvn -q -pl jclaim-core test-compile exec:java \
    -Dexec.mainClass=uk.codery.jclaim.examples.RetailQuickStart \
    -Dexec.classpathScope=test
```

Substitute `RetailQuickStart` for `ProductQuickStart` or
`PropertyQuickStart` to run the other demos. Each one also has a
companion test under
`jclaim-core/src/test/java/uk/codery/jclaim/examples/` so any quoted
snippet in the project [`README.md`](../README.md) cannot drift out of
date silently.

## `RetailQuickStart.java`

Loads five curated customers from
[`src/test/resources/retail-fixtures/`](../jclaim-core/src/test/resources/retail-fixtures/).

| Customer    | Source coverage                              |
|-------------|----------------------------------------------|
| `cust-001`  | all four sources                             |
| `cust-002`  | three sources (no in-store record)           |
| `cust-003`  | two sources with phone-format variation      |
| `cust-004`  | pos + loyalty link via loyalty number        |
| `cust-005`  | single-source customer                       |

See [`retail-fixtures/README.md`](../jclaim-core/src/test/resources/retail-fixtures/README.md)
for the dataset shape and the full set of scenarios it covers.

## `ProductQuickStart.java`

Loads five curated products from
[`src/test/resources/product-fixtures/`](../jclaim-core/src/test/resources/product-fixtures/).

| Product     | Source coverage                                          |
|-------------|----------------------------------------------------------|
| `prod-001`  | all four sources (shared GTIN)                           |
| `prod-002`  | three sources (private label, no supplier entry)         |
| `prod-003`  | two sources (pre-launch, PIM + supplier only)            |
| `prod-004`  | SKU-only product (warehouse-only, no GTIN)               |
| `prod-005`  | size variant of `prod-001` — distinct entity             |

See [`product-fixtures/README.md`](../jclaim-core/src/test/resources/product-fixtures/README.md)
for the dataset shape and scenario coverage.

## `PropertyQuickStart.java`

Loads five curated UK properties from
[`src/test/resources/property-fixtures/`](../jclaim-core/src/test/resources/property-fixtures/).

| Property    | Source coverage                                          |
|-------------|----------------------------------------------------------|
| `prop-001`  | all four sources (shared UPRN)                           |
| `prop-002`  | three sources (no Land Registry entry)                   |
| `prop-003`  | two sources (basic dwelling)                             |
| `prop-004`  | flat 1 of three in one converted Victorian house         |
| `prop-005`  | flat 2 of three — distinct entity from flat 1            |

See [`property-fixtures/README.md`](../jclaim-core/src/test/resources/property-fixtures/README.md)
for the dataset shape and scenario coverage.

## `MultiTypeQuickStart.java`

Reconciles **two entity types** — `customer` and `vehicle` — side by
side in one application, *without* Spring. Each type gets its own
`EntityResolver` over its own `InMemoryEntityStorage`, and both are
wrapped in an [`EntityResolvers`](../jclaim-core/src/main/java/uk/codery/jclaim/resolver/EntityResolvers.java)
registry (the selection facade — `forType` / `find` / `types`). This is
the non-Spring shape of what the starter's `jclaim.entity-types.<type>`
map builds.

It demonstrates the headline guarantee of **physical per-type
isolation**: the same `(crm, c-100)` alias resolves to *independent*
entities under `customer` and `vehicle`. It also uses type-specific
humanId templates (`CU-…`, `VH…`) to illustrate that humanId uniqueness
is per storage scope, not global. See the starter's
[Multiple entity types](../jclaim-spring-boot-starter/README.md#multiple-entity-types)
section for the Spring configuration.
