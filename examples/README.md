# Examples

End-to-end runnable demonstrations of JClaim against the retail synthetic
dataset.

## `RetailQuickStart.java`

Loads five curated customers from
[`src/test/resources/retail-fixtures/`](../src/test/resources/retail-fixtures/),
folds each customer's source-system records into the resolver one alias
at a time, and prints the resulting entity graph.

### Running

The class lives on the test classpath (the fixture loader is a
test-scope utility). From the project root:

```bash
mvn -q test-compile exec:java \
    -Dexec.mainClass=uk.codery.jclaim.examples.RetailQuickStart \
    -Dexec.classpathScope=test
```

Or run the class directly from your IDE: open
`examples/RetailQuickStart.java` and execute its `main` method against
the test classpath.

The companion test
`src/test/java/uk/codery/jclaim/examples/RetailQuickStartTest.java`
exercises the example on every build, so the snippet in the project
[`README.md`](../README.md#quick-start) cannot drift out of date silently.

### Customers exercised

| Customer    | Source coverage                              |
|-------------|----------------------------------------------|
| `cust-001`  | all four sources                             |
| `cust-002`  | three sources (no in-store record)           |
| `cust-003`  | two sources with phone-format variation      |
| `cust-004`  | pos + loyalty link via loyalty number        |
| `cust-005`  | single-source customer                       |

See [`retail-fixtures/README.md`](../src/test/resources/retail-fixtures/README.md)
for the dataset shape and the full set of scenarios it covers.
