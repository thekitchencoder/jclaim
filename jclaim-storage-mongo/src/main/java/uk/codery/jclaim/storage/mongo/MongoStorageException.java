package uk.codery.jclaim.storage.mongo;

/**
 * Wraps unexpected MongoDB driver errors thrown by the Mongo adapter.
 * Domain-level conditions ({@link uk.codery.jclaim.storage.AliasAlreadyClaimedException},
 * {@link uk.codery.jclaim.storage.EntityNotFoundException}, {@link IllegalStateException}
 * for publicId / URN collisions) surface as themselves; anything else lands here.
 */
public final class MongoStorageException extends RuntimeException {

    public MongoStorageException(String message) {
        super(message);
    }

    public MongoStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
