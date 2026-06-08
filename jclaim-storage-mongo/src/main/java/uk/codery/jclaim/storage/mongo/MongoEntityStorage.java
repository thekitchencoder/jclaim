package uk.codery.jclaim.storage.mongo;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * MongoDB {@link EntityStorage} adapter. Each canonical entity lives as a
 * single document in the configured collection, keyed by its URN
 * ({@code _id}). Aliases and attributes are stored as ordered arrays inside
 * the document; alias uniqueness across the collection is enforced by a
 * unique compound index on {@code (aliases.source, aliases.sourceId)} —
 * the atomic primitive the port relies on.
 *
 * <p>{@link #resolveOrCreate} invokes the mint factory at most once per
 * call: an optimistic alias lookup short-circuits the hit case, and a
 * duplicate-key error on the claim alias during {@code insertOne} returns
 * the racing winner as {@link StorageOutcome.Existing} without re-minting.
 *
 * <p>The adapter creates its indexes on construction by default. Pass
 * {@link Builder#createIndexes(boolean)} {@code false} when index management
 * is owned elsewhere.
 */
public final class MongoEntityStorage implements EntityStorage {

    private static final Logger log = LoggerFactory.getLogger(MongoEntityStorage.class);

    static final String FIELD_ID = "_id";
    static final String FIELD_PUBLIC_ID = "publicId";
    static final String FIELD_SUPERSEDED_BY = "supersededBy";
    static final String FIELD_CREATED_AT = "createdAt";
    static final String FIELD_UPDATED_AT = "updatedAt";
    static final String FIELD_ALIASES = "aliases";
    static final String FIELD_ATTRIBUTES = "attributes";
    static final String FIELD_ALIAS_SOURCE = "source";
    static final String FIELD_ALIAS_SOURCE_ID = "sourceId";
    static final String FIELD_ATTRIBUTE_NAME = "name";
    static final String FIELD_ATTRIBUTE_VALUE = "value";

    static final String INDEX_PUBLIC_ID = "jclaim_publicId_unique";
    static final String INDEX_ALIASES = "jclaim_aliases_unique";
    static final String INDEX_ATTRIBUTES = "jclaim_attributes_lookup";

    private final MongoCollection<Document> collection;

    private MongoEntityStorage(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    /** Builder for {@link MongoEntityStorage}. */
    public static Builder builder(MongoCollection<Document> collection) {
        return new Builder(collection);
    }

    /** Convenience factory that builds with defaults (indexes auto-created). */
    public static MongoEntityStorage create(MongoCollection<Document> collection) {
        return builder(collection).build();
    }

    @Override
    public Optional<Entity> findByUrn(EntityId urn) {
        Objects.requireNonNull(urn, "urn");
        Document doc = collection.find(Filters.eq(FIELD_ID, urn.urn())).first();
        return Optional.ofNullable(doc).map(MongoEntityStorage::toEntity);
    }

    @Override
    public Optional<Entity> findByPublicId(String publicId) {
        Objects.requireNonNull(publicId, "publicId");
        Document doc = collection.find(Filters.eq(FIELD_PUBLIC_ID, publicId)).first();
        return Optional.ofNullable(doc).map(MongoEntityStorage::toEntity);
    }

    @Override
    public Optional<Entity> findByAlias(Alias alias) {
        Objects.requireNonNull(alias, "alias");
        Document doc = collection.find(aliasFilter(alias)).first();
        return Optional.ofNullable(doc).map(MongoEntityStorage::toEntity);
    }

    @Override
    public StorageOutcome resolveOrCreate(Alias alias, Supplier<Entity> mintFactory) {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(mintFactory, "mintFactory");

        Document existing = collection.find(aliasFilter(alias)).first();
        if (existing != null) {
            return new StorageOutcome.Existing(toEntity(existing));
        }

        Entity minted = mintFactory.get();
        Objects.requireNonNull(minted, "mintFactory returned null");
        if (!minted.aliases().contains(alias)) {
            throw new IllegalStateException(
                    "Minted entity must carry the claim alias " + alias
                            + "; carried " + minted.aliases());
        }

        try {
            collection.insertOne(toDocument(minted));
            return new StorageOutcome.Created(minted);
        } catch (MongoWriteException ex) {
            if (ErrorCategory.fromErrorCode(ex.getError().getCode()) != ErrorCategory.DUPLICATE_KEY) {
                throw new MongoStorageException("resolveOrCreate insert failed for " + alias, ex);
            }
            return handleDuplicateKey(alias, minted, ex);
        }
    }

    private StorageOutcome handleDuplicateKey(Alias claimAlias, Entity minted, MongoWriteException ex) {
        // Race on the claim alias: another inserter got there first.
        Document winner = collection.find(aliasFilter(claimAlias)).first();
        if (winner != null) {
            log.debug("Race-loss on claim alias {} — returning existing entity {}",
                    claimAlias, winner.getString(FIELD_ID));
            return new StorageOutcome.Existing(toEntity(winner));
        }
        // Claim alias is still free → some other alias on the mint was already claimed.
        for (Alias other : minted.aliases()) {
            if (other.equals(claimAlias)) {
                continue;
            }
            Document owner = collection.find(aliasFilter(other)).first();
            if (owner != null) {
                throw new AliasAlreadyClaimedException(
                        other, new EntityId(owner.getString(FIELD_ID)));
            }
        }
        String message = ex.getError().getMessage();
        if (message != null && message.contains(INDEX_PUBLIC_ID)) {
            throw new IllegalStateException("publicId collision on mint: " + minted.publicId(), ex);
        }
        if (message != null && (message.contains("_id_") || message.contains("\"" + minted.id().urn() + "\""))) {
            throw new IllegalStateException("URN collision on mint: " + minted.id(), ex);
        }
        throw new MongoStorageException(
                "resolveOrCreate failed with duplicate-key but no offender identified", ex);
    }

    @Override
    public Entity addAlias(EntityId urn, Alias alias) {
        Objects.requireNonNull(urn, "urn");
        Objects.requireNonNull(alias, "alias");

        Document target = collection.find(Filters.eq(FIELD_ID, urn.urn())).first();
        if (target == null) {
            throw new EntityNotFoundException(urn);
        }
        if (entityCarriesAlias(target, alias)) {
            return toEntity(target);
        }

        Document existingOwner = collection.find(aliasFilter(alias)).first();
        if (existingOwner != null && !existingOwner.getString(FIELD_ID).equals(urn.urn())) {
            throw new AliasAlreadyClaimedException(
                    alias, new EntityId(existingOwner.getString(FIELD_ID)));
        }

        try {
            collection.updateOne(
                    Filters.eq(FIELD_ID, urn.urn()),
                    Updates.addToSet(FIELD_ALIASES, aliasDocument(alias)));
        } catch (MongoWriteException ex) {
            if (ErrorCategory.fromErrorCode(ex.getError().getCode()) == ErrorCategory.DUPLICATE_KEY) {
                Document raced = collection.find(aliasFilter(alias)).first();
                if (raced != null && !raced.getString(FIELD_ID).equals(urn.urn())) {
                    throw new AliasAlreadyClaimedException(
                            alias, new EntityId(raced.getString(FIELD_ID)));
                }
            }
            throw new MongoStorageException("addAlias failed for " + urn + "/" + alias, ex);
        }
        Document updated = collection.find(Filters.eq(FIELD_ID, urn.urn())).first();
        if (updated == null) {
            throw new EntityNotFoundException(urn);
        }
        return toEntity(updated);
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
        List<Bson> clauses = new ArrayList<>(1 + claim.attributes().size());
        clauses.add(aliasFilter(claim.asAlias()));
        for (MatchingAttribute attr : claim.attributes()) {
            clauses.add(Filters.elemMatch(FIELD_ATTRIBUTES,
                    Filters.and(
                            Filters.eq(FIELD_ATTRIBUTE_NAME, attr.name()),
                            Filters.eq(FIELD_ATTRIBUTE_VALUE, attr.value()))));
        }
        Bson query = clauses.size() == 1 ? clauses.get(0) : Filters.or(clauses);

        for (Document doc : collection.find(query).limit(limit)) {
            candidates.add(toEntity(doc));
        }
        return candidates;
    }

    // ── Document mapping ──────────────────────────────────────────────────

    private static Bson aliasFilter(Alias alias) {
        return Filters.elemMatch(FIELD_ALIASES,
                Filters.and(
                        Filters.eq(FIELD_ALIAS_SOURCE, alias.source().name()),
                        Filters.eq(FIELD_ALIAS_SOURCE_ID, alias.sourceId())));
    }

    private static boolean entityCarriesAlias(Document entityDoc, Alias alias) {
        List<Document> aliases = entityDoc.getList(FIELD_ALIASES, Document.class);
        if (aliases == null) {
            return false;
        }
        for (Document a : aliases) {
            if (alias.source().name().equals(a.getString(FIELD_ALIAS_SOURCE))
                    && alias.sourceId().equals(a.getString(FIELD_ALIAS_SOURCE_ID))) {
                return true;
            }
        }
        return false;
    }

    static Document toDocument(Entity entity) {
        Document doc = new Document();
        doc.put(FIELD_ID, entity.id().urn());
        // Omit publicId entirely when absent: a present-but-null value would
        // still satisfy the partial index's {$exists:true} filter and collide
        // with every other publicId-less entity on null.
        if (entity.publicId() != null) {
            doc.put(FIELD_PUBLIC_ID, entity.publicId());
        }
        doc.put(FIELD_SUPERSEDED_BY, entity.supersededBy() == null ? null : entity.supersededBy().urn());
        doc.put(FIELD_CREATED_AT, Date.from(entity.createdAt()));
        doc.put(FIELD_UPDATED_AT, Date.from(entity.updatedAt()));

        List<Document> aliases = new ArrayList<>(entity.aliases().size());
        for (Alias alias : entity.aliases()) {
            aliases.add(aliasDocument(alias));
        }
        doc.put(FIELD_ALIASES, aliases);

        List<Document> attributes = new ArrayList<>(entity.attributes().size());
        for (MatchingAttribute attr : entity.attributes()) {
            attributes.add(attributeDocument(attr));
        }
        doc.put(FIELD_ATTRIBUTES, attributes);

        return doc;
    }

    static Document aliasDocument(Alias alias) {
        return new Document()
                .append(FIELD_ALIAS_SOURCE, alias.source().name())
                .append(FIELD_ALIAS_SOURCE_ID, alias.sourceId());
    }

    static Document attributeDocument(MatchingAttribute attr) {
        return new Document()
                .append(FIELD_ATTRIBUTE_NAME, attr.name())
                .append(FIELD_ATTRIBUTE_VALUE, attr.value());
    }

    static Entity toEntity(Document doc) {
        String urn = doc.getString(FIELD_ID);
        String publicId = doc.getString(FIELD_PUBLIC_ID);
        String supersededByUrn = doc.getString(FIELD_SUPERSEDED_BY);
        Instant createdAt = doc.getDate(FIELD_CREATED_AT).toInstant();
        Instant updatedAt = doc.getDate(FIELD_UPDATED_AT).toInstant();

        List<Alias> aliases = new ArrayList<>();
        List<Document> aliasDocs = doc.getList(FIELD_ALIASES, Document.class);
        if (aliasDocs != null) {
            for (Document a : aliasDocs) {
                aliases.add(new Alias(
                        SourceSystem.of(a.getString(FIELD_ALIAS_SOURCE)),
                        a.getString(FIELD_ALIAS_SOURCE_ID)));
            }
        }

        List<MatchingAttribute> attributes = new ArrayList<>();
        List<Document> attrDocs = doc.getList(FIELD_ATTRIBUTES, Document.class);
        if (attrDocs != null) {
            for (Document a : attrDocs) {
                attributes.add(new MatchingAttribute(
                        a.getString(FIELD_ATTRIBUTE_NAME),
                        a.get(FIELD_ATTRIBUTE_VALUE)));
            }
        }

        return new Entity(
                new EntityId(urn),
                publicId,
                aliases,
                attributes,
                supersededByUrn == null ? null : new EntityId(supersededByUrn),
                createdAt,
                updatedAt);
    }

    // ── Index management ──────────────────────────────────────────────────

    private void createIndexes() {
        // Partial unique index: publicId is opt-in, so only documents that
        // carry the field participate. Entities minted without a publicId omit
        // the field entirely (see toDocument) and never collide on null.
        collection.createIndex(
                Indexes.ascending(FIELD_PUBLIC_ID),
                new IndexOptions().unique(true)
                        .partialFilterExpression(Filters.exists(FIELD_PUBLIC_ID, true))
                        .name(INDEX_PUBLIC_ID));
        collection.createIndex(
                Indexes.ascending(FIELD_ALIASES + "." + FIELD_ALIAS_SOURCE,
                        FIELD_ALIASES + "." + FIELD_ALIAS_SOURCE_ID),
                new IndexOptions().unique(true).name(INDEX_ALIASES));
        // Non-unique compound index supporting findCandidates' per-attribute
        // elemMatch queries. Not part of the port's uniqueness contract; purely
        // for retrieval efficiency.
        collection.createIndex(
                Indexes.ascending(FIELD_ATTRIBUTES + "." + FIELD_ATTRIBUTE_NAME,
                        FIELD_ATTRIBUTES + "." + FIELD_ATTRIBUTE_VALUE),
                new IndexOptions().name(INDEX_ATTRIBUTES));
    }

    // ── Builder ───────────────────────────────────────────────────────────

    /** Fluent builder for {@link MongoEntityStorage}. */
    public static final class Builder {
        private final MongoCollection<Document> collection;
        private boolean createIndexes = true;

        private Builder(MongoCollection<Document> collection) {
            this.collection = Objects.requireNonNull(collection, "collection");
        }

        /**
         * Controls whether the adapter creates the required unique indexes
         * on construction. Default is {@code true}.
         */
        public Builder createIndexes(boolean createIndexes) {
            this.createIndexes = createIndexes;
            return this;
        }

        public MongoEntityStorage build() {
            MongoEntityStorage storage = new MongoEntityStorage(collection);
            if (createIndexes) {
                storage.createIndexes();
            }
            return storage;
        }
    }
}
