package uk.codery.jclaim.storage;

import uk.codery.jclaim.model.EntityId;

/** Thrown when an operation targets an entity URN that storage has never recorded. */
public class EntityNotFoundException extends RuntimeException {

    private final EntityId urn;

    public EntityNotFoundException(EntityId urn) {
        super("No entity stored for URN " + urn);
        this.urn = urn;
    }

    public EntityId urn() {
        return urn;
    }
}
