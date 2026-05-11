# Product Fixtures

Synthetic product SKU dataset used by the JClaim integration tests and
the `examples/ProductQuickStart` runnable. Product reconciliation is
the canonical SKU-master / PIM-mastering example, recognisable to
anyone in the merchandising and supply-chain space, and demonstrates
that JClaim's entity-agnostic shape works as cleanly for products as
for the retail customer corpus.

## Files

| File             | Purpose                                                          |
|------------------|------------------------------------------------------------------|
| `products.yaml`  | 100 real-world products, each with one or more source records.   |
| `updates.yaml`   | Re-asserted claims with mutated attributes — conflict scenarios. |

## `products.yaml` shape

```yaml
products:
  - id: prod-001                  # synthetic ground-truth id (not the URN)
    notes: "optional commentary"
    records:
      pim:                        # one block per source system
        source_id: "pim-00001"    # the (source, source_id) alias key
        gtin: "..."
        ...
      warehouse:
        source_id: "wh-7788"
        ...
```

The file **is** the ground truth: every `records.*` entry under one
product must end up linked to the same canonical entity. The loader
exposes both a flat list of `Claim` objects (for ingestion) and a
`Map<String, List<Claim>>` keyed by `prod-id` (for assertions).

## Source systems and the attributes they capture

No two sources capture identical fields — asymmetry is what makes
reconciliation interesting.

| Source         | Captures                                                                                  |
|----------------|--------------------------------------------------------------------------------------------|
| `pim`          | `gtin`, `manufacturer_part_number`, `brand`, `name`, `category`, `dimensions_cm`, `weight_g` |
| `warehouse`    | `gtin` (when present), `internal_sku`, `weight_g`, `bin_location`                          |
| `marketplace`  | `gtin`, `listing_title`, `asin`                                                            |
| `supplier`     | `manufacturer_part_number`, `brand`, `product_family`                                      |

Attributes are carried into JClaim as `MatchingAttribute(name, value)`
pairs. Names use the same `snake_case` form as the YAML keys.

## Coverage distribution

| Source coverage  | Products | IDs                |
|------------------|---------:|--------------------|
| Single source    | 50       | prod-051..prod-100 |
| Two sources      | 25       | prod-026..prod-050 |
| Three sources    | 11       | prod-015..prod-025 |
| All four sources | 4        | prod-011..prod-014 |
| Curated scenario | 10       | prod-001..prod-010 |

The scenario products (`prod-001..prod-010`) are explicitly engineered
to exercise particular reconciliation patterns and are documented
inline in `products.yaml`. The bulk products provide volume and
distribution realism.

## Scenarios exercised

| Scenario                                                       | Where                                  |
|----------------------------------------------------------------|----------------------------------------|
| Product known to all four sources (shared GTIN)                | `prod-001`                             |
| Three-source coverage (private label, no supplier entry)       | `prod-002`                             |
| Pre-launch product (PIM + supplier, not yet stocked or listed) | `prod-003`                             |
| SKU-only product (warehouse-only, no GTIN)                     | `prod-004`                             |
| Size variant of an existing product (distinct entity)          | `prod-005`                             |
| Colour variant of an existing product (distinct entity)        | `prod-006`                             |
| Same-GTIN conflicting brand (data quality fix → conflict)      | `prod-007` plus `updates.yaml`         |
| Manufacturer rebranding (same MPN → conflict)                  | `prod-008` plus `updates.yaml`         |
| Two distinct products with similar names                       | `prod-009` / `prod-010`                |

The matching policy in this release is **exact-alias only**. GTIN-
and MPN-based matching scenarios in the data are wired up to exercise
the `addAlias` path (the future matching policy session will have
stronger predicates over the same fixtures).

## Conventions for adding cases

- Use clearly fictional brand names (`Acme`, `Globex`, `Initech`,
  `Hooli`, `Vandelay`, `Soylent`, `Tyrell`, `Cyberdyne`, `Aperture`,
  `Massive Dynamic`, `Stark Industries`).
- GTINs are 13-digit with valid check digits but the prefix `509990`
  is not allocated, so the codes do not collide with any real product.
- ASINs follow the synthetic shape `B0XXX####`.
- Manufacturer part numbers follow `MPN-<BrandInitial>-####`.
- Warehouse internal SKUs follow `WH-<BRAND>-<DESC>` for branded items
  and `WH-BULK-<DESC>` for SKU-only bulk items.
- Use ISO 8601 dates (`YYYY-MM-DD`) if needed.
- Add a `notes:` field whenever the entry encodes a scenario worth
  finding by humans skimming the file.
- Keep IDs zero-padded to three digits so they sort.
- For conflict scenarios, place the original record in
  `products.yaml` and the mutated re-assertion in `updates.yaml` —
  the loader keeps them in separate batches so tests can ingest the
  baseline first and assert clean, then introduce updates.

## Determinism

This dataset is a fixed test asset. It is **not** regenerated at test
time and contains no calls to Faker or similar libraries. Adding,
removing or editing entries will alter test assertions — extend with
care and update the integration tests in step.
