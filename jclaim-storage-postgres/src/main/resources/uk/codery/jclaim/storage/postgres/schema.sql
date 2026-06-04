-- JClaim Postgres adapter schema. Auto-applied on startup unless the
-- adapter is built with applySchema(false). Every statement is idempotent
-- (CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS) so repeated
-- application across boots is safe.
--
-- The schema is intentionally normalised: alias uniqueness is enforced by
-- a single PK on (source, source_id); each attribute is one row with a
-- jsonb value so non-string types round-trip cleanly. Foreign keys cascade
-- on entity deletion so future merge/supersede flows are tractable.

CREATE TABLE IF NOT EXISTS entities (
    urn            text PRIMARY KEY,
    human_id       text NULL,
    superseded_by  text NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);

-- humanId is opt-in: nullable, but unique when present. A partial unique
-- index enforces uniqueness only for non-null human_id values, so any
-- number of entities may carry no humanId without colliding on NULL. The
-- index name contains "human_id" so the adapter's unique-violation handler
-- still recognises a present-humanId collision.
CREATE UNIQUE INDEX IF NOT EXISTS entities_human_id_unique
    ON entities (human_id) WHERE human_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS entity_aliases (
    source       text NOT NULL,
    source_id    text NOT NULL,
    entity_urn   text NOT NULL REFERENCES entities (urn) ON DELETE CASCADE,
    attached_at  timestamptz NOT NULL,
    position     int NOT NULL,
    PRIMARY KEY (source, source_id)
);

CREATE INDEX IF NOT EXISTS idx_entity_aliases_entity_urn
    ON entity_aliases (entity_urn);

CREATE TABLE IF NOT EXISTS entity_attributes (
    entity_urn  text NOT NULL REFERENCES entities (urn) ON DELETE CASCADE,
    name        text NOT NULL,
    value       jsonb NOT NULL,
    position    int NOT NULL,
    PRIMARY KEY (entity_urn, name)
);

CREATE INDEX IF NOT EXISTS idx_entity_attributes_entity_urn
    ON entity_attributes (entity_urn);

-- Supports findCandidates' per-attribute (name, value) lookup. Not part of
-- any uniqueness contract; purely for retrieval efficiency.
CREATE INDEX IF NOT EXISTS idx_entity_attributes_name_value
    ON entity_attributes (name, value);
