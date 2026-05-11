package uk.codery.jclaim.storage;

import uk.codery.jclaim.model.Entity;

/**
 * Result of {@link EntityStorage#resolveOrCreate}. Discriminates whether the
 * alias index already pointed at an entity (in which case that entity is
 * returned unchanged) or a new entity was atomically inserted under the
 * supplied alias.
 */
public sealed interface StorageOutcome permits StorageOutcome.Existing, StorageOutcome.Created {

    /** The entity now associated with the alias. */
    Entity entity();

    /** The alias was already claimed; the stored entity is returned. */
    record Existing(Entity entity) implements StorageOutcome {
        public Existing {
            if (entity == null) {
                throw new NullPointerException("entity");
            }
        }
    }

    /** The alias was free; the supplied entity has been persisted. */
    record Created(Entity entity) implements StorageOutcome {
        public Created {
            if (entity == null) {
                throw new NullPointerException("entity");
            }
        }
    }
}
