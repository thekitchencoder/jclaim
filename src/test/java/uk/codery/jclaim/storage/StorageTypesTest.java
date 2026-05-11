package uk.codery.jclaim.storage;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.SourceSystem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageTypesTest {

    private final EntityId urn = EntityId.of(
            UUID.fromString("01900000-0000-7000-8000-000000000020"));
    private final Alias alias = Alias.of(SourceSystem.of("crm"), "abc");
    private final Entity entity = new Entity(
            urn, "0000-0000-0", List.of(alias), List.of(), null,
            Instant.EPOCH, Instant.EPOCH);

    @Test
    void storageOutcome_existing_carriesEntity() {
        StorageOutcome outcome = new StorageOutcome.Existing(entity);
        assertThat(outcome).isInstanceOf(StorageOutcome.Existing.class);
        assertThat(outcome.entity()).isEqualTo(entity);
    }

    @Test
    void storageOutcome_created_carriesEntity() {
        StorageOutcome outcome = new StorageOutcome.Created(entity);
        assertThat(outcome).isInstanceOf(StorageOutcome.Created.class);
        assertThat(outcome.entity()).isEqualTo(entity);
    }

    @Test
    void storageOutcome_rejectsNullEntity() {
        assertThatThrownBy(() -> new StorageOutcome.Existing(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StorageOutcome.Created(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aliasAlreadyClaimedException_exposesAliasAndOwner() {
        AliasAlreadyClaimedException ex = new AliasAlreadyClaimedException(alias, urn);
        assertThat(ex.alias()).isEqualTo(alias);
        assertThat(ex.existingOwner()).isEqualTo(urn);
        assertThat(ex.getMessage()).contains(alias.toString()).contains(urn.toString());
    }

    @Test
    void entityNotFoundException_exposesUrn() {
        EntityNotFoundException ex = new EntityNotFoundException(urn);
        assertThat(ex.urn()).isEqualTo(urn);
        assertThat(ex.getMessage()).contains(urn.toString());
    }
}
