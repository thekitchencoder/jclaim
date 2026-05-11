package uk.codery.jclaim.storage;

import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.EntityId;

/**
 * Thrown when an alias is added to an entity but the same alias is already
 * mapped to a different entity in the alias index.
 */
public class AliasAlreadyClaimedException extends RuntimeException {

    private final Alias alias;
    private final EntityId existingOwner;

    public AliasAlreadyClaimedException(Alias alias, EntityId existingOwner) {
        super("Alias " + alias + " is already claimed by " + existingOwner);
        this.alias = alias;
        this.existingOwner = existingOwner;
    }

    public Alias alias() {
        return alias;
    }

    public EntityId existingOwner() {
        return existingOwner;
    }
}
