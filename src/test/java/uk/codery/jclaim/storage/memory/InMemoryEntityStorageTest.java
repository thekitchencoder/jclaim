package uk.codery.jclaim.storage.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.storage.AliasAlreadyClaimedException;
import uk.codery.jclaim.storage.EntityNotFoundException;
import uk.codery.jclaim.storage.StorageOutcome;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryEntityStorageTest {

    private InMemoryEntityStorage storage;
    private final Alias aliceEcom = Alias.of(SourceSystem.of("ecommerce"), "cust-001");
    private final Alias alicePos = Alias.of(SourceSystem.of("pos"), "loyalty-99");
    private final Alias bobEcom = Alias.of(SourceSystem.of("ecommerce"), "cust-002");

    @BeforeEach
    void setUp() {
        storage = new InMemoryEntityStorage();
    }

    @Test
    void resolveOrCreate_freshAlias_persistsAndReturnsCreated() {
        Entity alice = aliceEntity();
        StorageOutcome outcome = storage.resolveOrCreate(aliceEcom, () -> alice);

        assertThat(outcome).isInstanceOf(StorageOutcome.Created.class);
        assertThat(outcome.entity()).isEqualTo(alice);
        assertThat(storage.size()).isEqualTo(1);
        assertThat(storage.findByUrn(alice.id())).contains(alice);
        assertThat(storage.findByHumanId(alice.humanId())).contains(alice);
        assertThat(storage.findByAlias(aliceEcom)).contains(alice);
    }

    @Test
    void resolveOrCreate_secondClaimSameAlias_returnsExistingWithoutInvokingFactory() {
        Entity alice = aliceEntity();
        storage.resolveOrCreate(aliceEcom, () -> alice);

        boolean[] factoryCalled = {false};
        StorageOutcome outcome = storage.resolveOrCreate(aliceEcom, () -> {
            factoryCalled[0] = true;
            return aliceEntity();
        });

        assertThat(factoryCalled[0]).isFalse();
        assertThat(outcome).isInstanceOf(StorageOutcome.Existing.class);
        assertThat(outcome.entity()).isEqualTo(alice);
        assertThat(storage.size()).isEqualTo(1);
    }

    @Test
    void resolveOrCreate_rejectsFactoryThatOmitsClaimAlias() {
        // Factory returns an entity whose alias list lacks aliceEcom — adapter must refuse.
        Entity unrelated = entityCarrying(List.of(bobEcom));
        assertThatThrownBy(() -> storage.resolveOrCreate(aliceEcom, () -> unrelated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must carry the claim alias");
    }

    @Test
    void resolveOrCreate_rejectsHumanIdCollision() {
        Entity alice = aliceEntity();
        storage.resolveOrCreate(aliceEcom, () -> alice);

        Entity colliding = new Entity(
                EntityId.of(UUID.randomUUID()),
                alice.humanId(),                    // <-- same humanId
                List.of(bobEcom),
                List.of(),
                null,
                Instant.now(),
                Instant.now()
        );
        assertThatThrownBy(() -> storage.resolveOrCreate(bobEcom, () -> colliding))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("humanId collision");
    }

    @Test
    void addAlias_attachesAliasToExistingEntity() {
        Entity alice = aliceEntity();
        storage.resolveOrCreate(aliceEcom, () -> alice);

        Entity updated = storage.addAlias(alice.id(), alicePos);

        assertThat(updated.aliases()).containsExactlyInAnyOrder(aliceEcom, alicePos);
        assertThat(storage.findByAlias(alicePos)).contains(updated);
    }

    @Test
    void addAlias_isIdempotentForAliasAlreadyOnEntity() {
        Entity alice = aliceEntity();
        storage.resolveOrCreate(aliceEcom, () -> alice);

        Entity updated = storage.addAlias(alice.id(), aliceEcom);
        assertThat(updated).isEqualTo(alice);
    }

    @Test
    void addAlias_rejectsAliasOwnedByAnotherEntity() {
        Entity alice = aliceEntity();
        Entity bob = entityCarrying(List.of(bobEcom));
        storage.resolveOrCreate(aliceEcom, () -> alice);
        storage.resolveOrCreate(bobEcom, () -> bob);

        assertThatThrownBy(() -> storage.addAlias(bob.id(), aliceEcom))
                .isInstanceOf(AliasAlreadyClaimedException.class);
    }

    @Test
    void addAlias_unknownUrn_throwsEntityNotFound() {
        EntityId nowhere = EntityId.of(UUID.randomUUID());
        assertThatThrownBy(() -> storage.addAlias(nowhere, aliceEcom))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private Entity aliceEntity() {
        return entityCarrying(List.of(aliceEcom));
    }

    private Entity entityCarrying(List<Alias> aliases) {
        return new Entity(
                EntityId.of(UUID.randomUUID()),
                deterministicHumanId(aliases),
                aliases,
                List.of(MatchingAttribute.of("email", "alice@example.com")),
                null,
                Instant.now(),
                Instant.now()
        );
    }

    /** Stable, valid-format human ID derived from the alias list — keeps tests deterministic. */
    private static String deterministicHumanId(List<Alias> aliases) {
        long seed = aliases.hashCode() & ((1L << 40) - 1L);
        return uk.codery.jclaim.id.HumanIdGenerator.format(seed);
    }
}
