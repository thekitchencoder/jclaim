# Retail Fixtures

Synthetic retail customer dataset used by the JClaim integration tests
and the `examples/RetailQuickStart` runnable. Retail customer
reconciliation is the canonical MDM example, recognisable to anyone in
the data-quality space, and provides a non-sensitive demonstration
corpus suitable for README examples and public documentation.

## Files

| File             | Purpose                                                          |
|------------------|------------------------------------------------------------------|
| `customers.yaml` | 100 real-world customers, each with one or more source records.  |
| `updates.yaml`   | Re-asserted claims with mutated attributes — conflict scenarios. |

## `customers.yaml` shape

```yaml
customers:
  - id: cust-001                  # synthetic ground-truth id (not the URN)
    notes: "optional commentary"
    records:
      ecommerce:                  # one block per source system
        source_id: "ec-12345"     # the (source, source_id) alias key
        email: "..."
        first_name: "..."
        ...
      pos:
        source_id: "pos-78910"
        ...
```

The file **is** the ground truth: every `records.*` entry under one
customer must end up linked to the same canonical entity. The loader
exposes both a flat list of `Claim` objects (for ingestion) and a
`Map<String, List<Claim>>` keyed by `cust-id` (for assertions).

## Source systems and the attributes they capture

No two sources capture identical fields — asymmetry is what makes
reconciliation interesting.

| Source      | Captures                                                              |
|-------------|------------------------------------------------------------------------|
| `ecommerce` | `email`, `first_name`, `last_name`, `phone`, `registered_at`           |
| `pos`       | `phone`, `loyalty_number` (sometimes), `first_seen`                    |
| `loyalty`   | `email`, `first_name`, `last_name`, `date_of_birth`, `postcode`        |
| `crm`       | `email`, `preferred_contact`, `case_count`                             |

Attributes are carried into JClaim as `MatchingAttribute(name, value)`
pairs. Names use the same `snake_case` form as the YAML keys.

## Coverage distribution

| Source coverage  | Customers | IDs                |
|------------------|----------:|--------------------|
| Single source    | 50        | cust-011..cust-060 |
| Two sources      | 25        | cust-061..cust-085 |
| Three sources    | 11        | cust-086..cust-096 |
| All four sources | 4         | cust-097..cust-100 |
| Curated scenario | 10        | cust-001..cust-010 |

The scenario customers (`cust-001..cust-010`) are explicitly engineered
to exercise particular reconciliation patterns and are documented
inline in `customers.yaml`. The bulk customers provide volume and
distribution realism.

## Scenarios exercised

| Scenario                                              | Where           |
|-------------------------------------------------------|-----------------|
| Customer known to all four sources                    | `cust-001`      |
| Three-source coverage (e-com + loyalty + crm)         | `cust-002`      |
| Phone-format variation across sources                 | `cust-003`      |
| Pos + loyalty linked via loyalty number               | `cust-004`      |
| Single-source customer                                | `cust-005`      |
| Two distinct customers with the same display name     | `cust-006/007`  |
| Two distinct customers sharing a phone number         | `cust-008/009`  |
| Conflict event when attributes diverge                | `cust-010` plus `updates.yaml` |

The matching policy in this release is **exact-alias only**. Email-
and phone-based matching scenarios in the data are wired up to
exercise the `addAlias` path (the future matching policy session will
have stronger predicates over the same fixtures).

## Conventions for adding cases

- Use `@example.com` for every email (RFC 2606 reserved domain).
- Use UK phones in the `+44 7700 9xxxxx` range (Ofcom-reserved).
- Use ISO 8601 dates (`YYYY-MM-DD`).
- Add a `notes:` field whenever the entry encodes a scenario worth
  finding by humans skimming the file.
- Keep IDs zero-padded to three digits so they sort.
- For conflict scenarios, place the original record in
  `customers.yaml` and the mutated re-assertion in `updates.yaml` —
  the loader keeps them in separate batches so tests can ingest the
  baseline first and assert clean, then introduce updates.

## Determinism

This dataset is a fixed test asset. It is **not** regenerated at test
time and contains no calls to Faker or similar libraries. Adding,
removing or editing entries will alter test assertions — extend with
care and update the integration tests in step.
