# Property Fixtures

Synthetic UK property dataset used by the JClaim integration tests and
the `examples/PropertyQuickStart` runnable. Property reconciliation
across PAF, AddressBase, Land Registry, and council tax extracts is the
canonical "building mastering" MDM problem in the UK public-sector
data space, and demonstrates that JClaim's entity-agnostic shape works
as cleanly for places as for retail customers or product SKUs.

## Files

| File              | Purpose                                                          |
|-------------------|------------------------------------------------------------------|
| `properties.yaml` | 100 real-world properties, each with one or more source records. |
| `updates.yaml`    | Re-asserted claims with mutated attributes — conflict scenarios. |

## `properties.yaml` shape

```yaml
properties:
  - id: prop-001                  # synthetic ground-truth id (not the URN)
    notes: "optional commentary"
    records:
      royal_mail_paf:             # one block per source system
        source_id: "paf-..."      # the (source, source_id) alias key
        uprn: "..."
        ...
      os_addressbase:
        source_id: "osab-..."
        ...
```

The file **is** the ground truth: every `records.*` entry under one
property must end up linked to the same canonical entity. The loader
exposes both a flat list of `Claim` objects (for ingestion) and a
`Map<String, List<Claim>>` keyed by `prop-id` (for assertions).

## Source systems and the attributes they capture

No two sources capture identical fields — asymmetry is what makes
reconciliation interesting.

| Source            | Captures                                                                                  |
|-------------------|--------------------------------------------------------------------------------------------|
| `royal_mail_paf`  | `uprn`, `postcode`, `address_line_1`, `thoroughfare`, `building_number`, `sub_building`    |
| `os_addressbase`  | `uprn`, `postcode`, `classification`, `easting`, `northing`                                |
| `land_registry`   | `uprn`, `title_number`, `tenure`, `registered_at`                                          |
| `council_tax`     | `uprn`, `postcode`, `band`                                                                 |

Attributes are carried into JClaim as `MatchingAttribute(name, value)`
pairs. Names use the same `snake_case` form as the YAML keys.

## Coverage distribution

| Source coverage  | Properties | IDs                |
|------------------|-----------:|--------------------|
| Single source    | 50         | prop-051..prop-100 |
| Two sources      | 25         | prop-026..prop-050 |
| Three sources    | 11         | prop-015..prop-025 |
| All four sources | 4          | prop-011..prop-014 |
| Curated scenario | 10         | prop-001..prop-010 |

The scenario properties (`prop-001..prop-010`) are explicitly engineered
to exercise particular reconciliation patterns and are documented
inline in `properties.yaml`. The bulk properties provide volume and
distribution realism.

## Scenarios exercised

| Scenario                                                            | Where                          |
|---------------------------------------------------------------------|--------------------------------|
| Property known to all four sources (shared UPRN)                    | `prop-001`                     |
| Three-source coverage (unregistered land, no LR entry)              | `prop-002`                     |
| Two-source coverage (basic dwelling)                                | `prop-003`                     |
| Multiple flats in one building (distinct UPRNs, shared address)     | `prop-004`/`prop-005`/`prop-006` |
| Address representation variation (same UPRN, variant strings)       | `prop-007`                     |
| UPRN with conflicting postcode (renumbering → conflict event)       | `prop-008` plus `updates.yaml` |
| New build with provisional address before UPRN allocation           | `prop-009` plus `updates.yaml` |
| Two distinct properties with similar addresses                      | `prop-010` / `prop-026`        |

The matching policy in this release is **exact-alias only**. UPRN-
and address-based matching scenarios in the data are wired up to
exercise the `addAlias` path (the future matching policy session will
have stronger predicates over the same fixtures).

## Conventions for adding cases

- UPRNs sit in the unallocated `100099xxxxxx` range so they cannot
  collide with live UK property records.
- Postcodes mix landmark postcodes (e.g. `SW1A 1AA`, `EH99 1SP`) with
  fully fictional postcodes such as `XX1 1XX`.
- Title numbers follow real UK Land Registry shape: a 2-3 letter
  region prefix + 6 digits (e.g. `NGL123456`, `WLD100120`).
- Street names are deliberately generic so the synthetic UPRN and
  postcode combination signals the data is not a real address.
- Eastings / Northings approximate the UK National Grid but are
  rounded so they're clearly synthetic.
- Use ISO 8601 dates (`YYYY-MM-DD`) for `registered_at`.
- Add a `notes:` field whenever the entry encodes a scenario worth
  finding by humans skimming the file.
- Keep IDs zero-padded to three digits so they sort.
- For conflict scenarios, place the original record in
  `properties.yaml` and the mutated re-assertion in `updates.yaml` —
  the loader keeps them in separate batches so tests can ingest the
  baseline first and assert clean, then introduce updates.

## Determinism

This dataset is a fixed test asset. It is **not** regenerated at test
time and contains no calls to Faker or similar libraries. Adding,
removing or editing entries will alter test assertions — extend with
care and update the integration tests in step.
