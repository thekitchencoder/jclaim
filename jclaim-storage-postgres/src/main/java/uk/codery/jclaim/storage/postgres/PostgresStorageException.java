package uk.codery.jclaim.storage.postgres;

/**
 * Wraps unexpected {@link java.sql.SQLException}s thrown by the Postgres
 * adapter. Domain-level conditions ({@link uk.codery.jclaim.storage.AliasAlreadyClaimedException},
 * {@link uk.codery.jclaim.storage.EntityNotFoundException}, {@link IllegalStateException}
 * for humanId / URN collisions) surface as themselves; anything else lands
 * here.
 */
public final class PostgresStorageException extends RuntimeException {

    public PostgresStorageException(String message) {
        super(message);
    }

    public PostgresStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
