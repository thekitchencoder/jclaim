package uk.codery.jclaim.storage.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.storage.AliasAlreadyClaimedException;
import uk.codery.jclaim.storage.EntityNotFoundException;
import uk.codery.jclaim.storage.EntityStorage;
import uk.codery.jclaim.storage.StorageOutcome;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * PostgreSQL {@link EntityStorage} adapter built on plain JDBC. The schema is
 * normalised into {@code entities}, {@code entity_aliases} and
 * {@code entity_attributes}; alias uniqueness is enforced by the
 * {@code entity_aliases} primary key on {@code (source, source_id)}, which
 * is the atomic primitive the port relies on.
 *
 * <p>{@link #resolveOrCreate} invokes the mint factory at most once per call:
 * on the optimistic check-then-insert path, a race-loss on the claim alias
 * means another transaction inserted that alias first, so the factory's
 * candidate is discarded and the winner is returned as {@link
 * StorageOutcome.Existing}. The factory is not re-invoked, preserving the
 * port contract.
 *
 * <p>By default the adapter applies its schema on construction. Set
 * {@link Builder#applySchema(boolean)} to {@code false} when DDL is managed
 * externally (Flyway, Liquibase, your platform's migration tool of choice).
 */
public final class PostgresEntityStorage implements EntityStorage {

    private static final Logger log = LoggerFactory.getLogger(PostgresEntityStorage.class);
    private static final String SCHEMA_RESOURCE = "schema.sql";
    private static final String PG_UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    /** Target Postgres schema, or {@code null} when unscoped (today's behaviour). */
    private final String schema;
    /** Schema-qualifying table prefix: {@code ""} when unscoped, else {@code "\"<schema>\"."}. */
    private final String tablePrefix;
    /** Schema-qualified table tokens, derived from {@link #tablePrefix}. */
    private final String tEntities;
    private final String tAliases;
    private final String tAttributes;

    private PostgresEntityStorage(DataSource dataSource, ObjectMapper objectMapper, String schema) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        if (schema != null && !schema.isBlank()) {
            // Validate before the name is ever interpolated into SQL — identifier-safe
            // and injection-safe (same grammar as URN namespace/type segments).
            EntityId.requireValidSegment("schema", schema);
            this.schema = schema;
            this.tablePrefix = "\"" + schema + "\".";
        } else {
            this.schema = null;
            this.tablePrefix = "";
        }
        // When unscoped (prefix ""), these are the bare table names — SQL is
        // textually identical to today's single-type path.
        this.tEntities = tablePrefix + "entities";
        this.tAliases = tablePrefix + "entity_aliases";
        this.tAttributes = tablePrefix + "entity_attributes";
    }

    /** Builder for {@link PostgresEntityStorage}. */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    /** Convenience factory that builds with defaults (schema auto-applied). */
    public static PostgresEntityStorage create(DataSource dataSource) {
        return builder(dataSource).build();
    }

    @Override
    public Optional<Entity> findByUrn(EntityId urn) {
        Objects.requireNonNull(urn, "urn");
        try (Connection conn = dataSource.getConnection()) {
            return loadEntity(conn, urn.urn());
        } catch (SQLException ex) {
            throw new PostgresStorageException("findByUrn failed for " + urn, ex);
        }
    }

    @Override
    public Optional<Entity> findByPublicId(String publicId) {
        Objects.requireNonNull(publicId, "publicId");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT urn FROM " + tEntities + " WHERE public_id = ?")) {
            ps.setString(1, publicId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return loadEntity(conn, rs.getString(1));
            }
        } catch (SQLException ex) {
            throw new PostgresStorageException("findByPublicId failed for " + publicId, ex);
        }
    }

    @Override
    public Optional<Entity> findByAlias(Alias alias) {
        Objects.requireNonNull(alias, "alias");
        try (Connection conn = dataSource.getConnection()) {
            String urn = findEntityUrnForAlias(conn, alias);
            if (urn == null) {
                return Optional.empty();
            }
            return loadEntity(conn, urn);
        } catch (SQLException ex) {
            throw new PostgresStorageException("findByAlias failed for " + alias, ex);
        }
    }

    @Override
    public StorageOutcome resolveOrCreate(Alias alias, Supplier<Entity> mintFactory) {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(mintFactory, "mintFactory");

        // Optimistic check — if the alias already maps to an entity, the factory
        // must not be invoked.
        try (Connection conn = dataSource.getConnection()) {
            String existingUrn = findEntityUrnForAlias(conn, alias);
            if (existingUrn != null) {
                return new StorageOutcome.Existing(loadEntity(conn, existingUrn)
                        .orElseThrow(() -> new PostgresStorageException(
                                "Alias index points at missing entity: " + existingUrn)));
            }
        } catch (SQLException ex) {
            throw new PostgresStorageException("resolveOrCreate pre-check failed for " + alias, ex);
        }

        Entity minted = mintFactory.get();
        Objects.requireNonNull(minted, "mintFactory returned null");
        if (!minted.aliases().contains(alias)) {
            throw new IllegalStateException(
                    "Minted entity must carry the claim alias " + alias
                            + "; carried " + minted.aliases());
        }

        // Transactional insert. Any constraint violation aborts the whole
        // mint so partial state cannot leak.
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                insertEntityRow(conn, minted);
                // Insert aliases in the order they appear on the minted entity so
                // the read-side ORDER BY position recovers the insertion order
                // the in-memory adapter exposes via its List<Alias>.
                int position = 0;
                for (Alias other : minted.aliases()) {
                    insertAliasRow(conn, other, minted.id(), minted.createdAt(), position++);
                }
                insertAttributes(conn, minted);
                conn.commit();
                return new StorageOutcome.Created(minted);
            } catch (SQLException ex) {
                conn.rollback();
                if (PG_UNIQUE_VIOLATION.equals(ex.getSQLState())) {
                    return handleUniqueViolation(conn, alias, minted, ex);
                }
                throw new PostgresStorageException("resolveOrCreate insert failed for " + alias, ex);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new PostgresStorageException("resolveOrCreate connection failed for " + alias, ex);
        }
    }

    private StorageOutcome handleUniqueViolation(
            Connection conn, Alias claimAlias, Entity minted, SQLException ex) throws SQLException {
        String constraint = ex.getMessage();
        // Race lost on the claim alias — re-read and return Existing without
        // invoking the factory again.
        String existingUrn = findEntityUrnForAlias(conn, claimAlias);
        if (existingUrn != null) {
            log.debug("Race-loss on claim alias {} — returning existing entity {}", claimAlias, existingUrn);
            return new StorageOutcome.Existing(loadEntity(conn, existingUrn)
                    .orElseThrow(() -> new PostgresStorageException(
                            "Alias index points at missing entity: " + existingUrn, ex)));
        }
        // Claim alias is free, so the conflict is elsewhere. Walk the other
        // aliases on the mint to find the offender.
        for (Alias other : minted.aliases()) {
            if (other.equals(claimAlias)) {
                continue;
            }
            String owner = findEntityUrnForAlias(conn, other);
            if (owner != null) {
                throw new AliasAlreadyClaimedException(other, new EntityId(owner));
            }
        }
        // publicId or URN collision — surface as the existing in-memory adapter does.
        if (constraint != null && constraint.contains("public_id")) {
            throw new IllegalStateException(
                    "publicId collision on mint: " + minted.publicId(), ex);
        }
        if (constraint != null && constraint.contains("entities_pkey")) {
            throw new IllegalStateException("URN collision on mint: " + minted.id(), ex);
        }
        throw new PostgresStorageException(
                "resolveOrCreate failed with unique-violation but no offender identified", ex);
    }

    @Override
    public Entity addAlias(EntityId urn, Alias alias) {
        Objects.requireNonNull(urn, "urn");
        Objects.requireNonNull(alias, "alias");

        try (Connection conn = dataSource.getConnection()) {
            // Existence check first so we surface the right exception type.
            if (!entityExists(conn, urn.urn())) {
                throw new EntityNotFoundException(urn);
            }

            String existingOwner = findEntityUrnForAlias(conn, alias);
            if (existingOwner != null) {
                if (existingOwner.equals(urn.urn())) {
                    // Idempotent: alias already on this entity.
                    return loadEntity(conn, urn.urn())
                            .orElseThrow(() -> new EntityNotFoundException(urn));
                }
                throw new AliasAlreadyClaimedException(alias, new EntityId(existingOwner));
            }

            try {
                int nextPosition = nextAliasPosition(conn, urn.urn());
                insertAliasRow(conn, alias, urn, java.time.Instant.now(), nextPosition);
            } catch (SQLException ex) {
                if (PG_UNIQUE_VIOLATION.equals(ex.getSQLState())) {
                    // Race: another inserter grabbed the alias between our check and insert.
                    String newOwner = findEntityUrnForAlias(conn, alias);
                    if (newOwner != null && !newOwner.equals(urn.urn())) {
                        throw new AliasAlreadyClaimedException(
                                alias, new EntityId(newOwner));
                    }
                }
                throw new PostgresStorageException("addAlias insert failed for " + alias, ex);
            }
            return loadEntity(conn, urn.urn())
                    .orElseThrow(() -> new EntityNotFoundException(urn));
        } catch (SQLException ex) {
            throw new PostgresStorageException("addAlias failed for " + urn + "/" + alias, ex);
        }
    }

    @Override
    public Set<Entity> findCandidates(Claim claim, int limit) {
        Objects.requireNonNull(claim, "claim");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
        Set<Entity> candidates = new LinkedHashSet<>();
        if (limit == 0) {
            return candidates;
        }
        Set<String> candidateUrns = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection()) {
            String aliasOwner = findEntityUrnForAlias(conn, claim.asAlias());
            if (aliasOwner != null) {
                candidateUrns.add(aliasOwner);
            }
            if (candidateUrns.size() < limit && !claim.attributes().isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT DISTINCT entity_urn FROM " + tAttributes + " "
                                + "WHERE name = ? AND value = ?::jsonb LIMIT ?")) {
                    attributeLoop:
                    for (MatchingAttribute attr : claim.attributes()) {
                        ps.setString(1, attr.name());
                        ps.setString(2, objectMapper.writeValueAsString(attr.value()));
                        ps.setInt(3, limit);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                candidateUrns.add(rs.getString(1));
                                if (candidateUrns.size() == limit) {
                                    break attributeLoop;
                                }
                            }
                        }
                    }
                }
            }
            for (String urn : candidateUrns) {
                loadEntity(conn, urn).ifPresent(candidates::add);
            }
            return candidates;
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new PostgresStorageException("findCandidates failed for " + claim, ex);
        }
    }

    // ── Row I/O ───────────────────────────────────────────────────────────

    private boolean entityExists(Connection conn, String urn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM " + tEntities + " WHERE urn = ?")) {
            ps.setString(1, urn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String findEntityUrnForAlias(Connection conn, Alias alias) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT entity_urn FROM " + tAliases + " WHERE source = ? AND source_id = ?")) {
            ps.setString(1, alias.source().name());
            ps.setString(2, alias.sourceId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private Optional<Entity> loadEntity(Connection conn, String urn) throws SQLException {
        String publicId;
        String supersededBy;
        java.time.Instant createdAt;
        java.time.Instant updatedAt;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT public_id, superseded_by, created_at, updated_at FROM " + tEntities + " WHERE urn = ?")) {
            ps.setString(1, urn);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                publicId = rs.getString(1);
                supersededBy = rs.getString(2);
                createdAt = rs.getTimestamp(3).toInstant();
                updatedAt = rs.getTimestamp(4).toInstant();
            }
        }

        List<Alias> aliases = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT source, source_id FROM " + tAliases + " "
                        + "WHERE entity_urn = ? ORDER BY position")) {
            ps.setString(1, urn);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    aliases.add(new Alias(SourceSystem.of(rs.getString(1)), rs.getString(2)));
                }
            }
        }

        List<MatchingAttribute> attributes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name, value FROM " + tAttributes + " "
                        + "WHERE entity_urn = ? ORDER BY position")) {
            ps.setString(1, urn);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String json = rs.getString(2);
                    attributes.add(new MatchingAttribute(name, deserializeValue(json)));
                }
            }
        }

        return Optional.of(new Entity(
                new EntityId(urn),
                publicId,
                aliases,
                attributes,
                supersededBy == null ? null : new EntityId(supersededBy),
                createdAt,
                updatedAt));
    }

    private void insertEntityRow(Connection conn, Entity entity) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + tEntities + " (urn, public_id, superseded_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, entity.id().urn());
            ps.setString(2, entity.publicId());
            ps.setString(3, entity.supersededBy() == null ? null : entity.supersededBy().urn());
            ps.setTimestamp(4, Timestamp.from(entity.createdAt()));
            ps.setTimestamp(5, Timestamp.from(entity.updatedAt()));
            ps.executeUpdate();
        }
    }

    private void insertAliasRow(Connection conn, Alias alias, EntityId urn,
                                java.time.Instant attachedAt, int position)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + tAliases + " (source, source_id, entity_urn, attached_at, position) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, alias.source().name());
            ps.setString(2, alias.sourceId());
            ps.setString(3, urn.urn());
            ps.setTimestamp(4, Timestamp.from(attachedAt));
            ps.setInt(5, position);
            ps.executeUpdate();
        }
    }

    private int nextAliasPosition(Connection conn, String urn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM " + tAliases + " WHERE entity_urn = ?")) {
            ps.setString(1, urn);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void insertAttributes(Connection conn, Entity entity) throws SQLException {
        if (entity.attributes().isEmpty()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + tAttributes + " (entity_urn, name, value, position) VALUES (?, ?, ?, ?)")) {
            int position = 0;
            for (MatchingAttribute attr : entity.attributes()) {
                ps.setString(1, entity.id().urn());
                ps.setString(2, attr.name());
                ps.setObject(3, jsonbValue(attr.value()));
                ps.setInt(4, position++);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── Value serialisation ───────────────────────────────────────────────

    private PGobject jsonbValue(Object value) {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(objectMapper.writeValueAsString(value));
        } catch (SQLException | JsonProcessingException ex) {
            throw new PostgresStorageException(
                    "Could not serialise attribute value of type " + value.getClass(), ex);
        }
        return obj;
    }

    private Object deserializeValue(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException ex) {
            throw new PostgresStorageException(
                    "Could not deserialise attribute value JSON: " + json, ex);
        }
    }

    // ── Schema initialisation ─────────────────────────────────────────────

    private void applySchema() {
        String ddl = loadSchemaSql();
        try (Connection conn = dataSource.getConnection()) {
            if (schema == null) {
                applyDdl(conn, ddl);
                return;
            }
            // Provision the target schema, then steer the shipped (unqualified)
            // schema.sql DDL into it. We use SET LOCAL inside an explicit
            // transaction rather than a plain SET, because dataSource may be a
            // connection pool: a plain SET search_path would persist on the
            // physical connection after close() returns it to the pool, silently
            // affecting other (non-jclaim, unqualified) queries that later borrow
            // it. SET LOCAL is discarded at commit, so nothing leaks. jclaim's own
            // runtime queries are schema-qualified and never rely on search_path.
            // The schema name is validated in the ctor.
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
                stmt.execute("SET LOCAL search_path TO \"" + schema + "\"");
                for (String statement : splitStatements(ddl)) {
                    if (!statement.isBlank()) {
                        stmt.execute(statement);
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException ex) {
            throw new PostgresStorageException("Failed to apply schema.sql", ex);
        }
    }

    private void applyDdl(Connection conn, String ddl) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(ddl)) {
                if (!statement.isBlank()) {
                    stmt.execute(statement);
                }
            }
        }
    }

    private static String loadSchemaSql() {
        try (InputStream in = PostgresEntityStorage.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new PostgresStorageException(
                        "schema.sql not found alongside PostgresEntityStorage");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException ex) {
            throw new PostgresStorageException("Failed to read schema.sql", ex);
        }
    }

    private static List<String> splitStatements(String ddl) {
        // The shipped schema.sql uses `;` only at statement terminators (no
        // embedded semicolons in comments-after-statement). A naïve split is
        // sufficient; if richer DDL is added later, swap in a real parser.
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : ddl.split("\n")) {
            String trimmed = line.replaceAll("--.*$", "").trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            current.append(' ').append(trimmed);
            if (trimmed.endsWith(";")) {
                String s = current.toString().trim();
                statements.add(s.substring(0, s.length() - 1));
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    // ── Builder ───────────────────────────────────────────────────────────

    /** Fluent builder for {@link PostgresEntityStorage}. */
    public static final class Builder {
        private final DataSource dataSource;
        private boolean applySchema = true;
        private ObjectMapper objectMapper = new ObjectMapper();
        private String schema;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        }

        /**
         * Controls whether the adapter applies {@code schema.sql} on
         * construction. Default is {@code true}; set to {@code false} when
         * DDL is managed externally (Flyway, Liquibase, etc.).
         */
        public Builder applySchema(boolean applySchema) {
            this.applySchema = applySchema;
            return this;
        }

        /**
         * Replaces the {@link ObjectMapper} used for jsonb attribute
         * (de)serialisation. The default mapper has no custom configuration.
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            return this;
        }

        /**
         * Scopes the adapter to a dedicated Postgres schema so each entity type
         * can live in its own fully-isolated schema. {@code null} or blank (the
         * default) leaves the adapter unscoped — today's single-type behaviour
         * with unqualified table names. A non-blank value is validated as an
         * identifier-safe URN segment and used to schema-qualify every table
         * reference; with {@code applySchema(true)} the schema is created on
         * construction.
         */
        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        public PostgresEntityStorage build() {
            PostgresEntityStorage storage = new PostgresEntityStorage(dataSource, objectMapper, schema);
            if (applySchema) {
                storage.applySchema();
            }
            return storage;
        }
    }
}
