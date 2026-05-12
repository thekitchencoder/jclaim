package uk.codery.jclaim.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.codery.jclaim.id.HumanIdGenerator;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural contract every {@link EntityStorage} adapter must satisfy. The
 * suite is the architectural backbone of the storage port — any adapter that
 * passes is a drop-in replacement for the in-memory reference adapter.
 *
 * <p>Concrete subclasses implement {@link #newStorage()} (called per test) and
 * inherit every assertion. Tests deliberately construct entities directly
 * rather than via a resolver so the suite exercises the storage primitive in
 * isolation from minting policy.
 *
 * <p>Timestamps are truncated to millisecond precision because BSON stores
 * timestamps as 64-bit millis and Postgres {@code timestamptz} stores
 * microseconds — a contract relying on Java {@link Instant}'s nanosecond
 * precision would fail equality round-trips on those backends without
 * indicating any real defect.
 */
public abstract class EntityStorageContract {

    protected static final SourceSystem ECOM = SourceSystem.of("ecommerce");
    protected static final SourceSystem POS = SourceSystem.of("pos");
    protected static final SourceSystem CRM = SourceSystem.of("crm");

    protected static final Alias ALICE_ECOM = Alias.of(ECOM, "cust-001");
    protected static final Alias ALICE_POS = Alias.of(POS, "loyalty-99");
    protected static final Alias ALICE_CRM = Alias.of(CRM, "crm-77");
    protected static final Alias BOB_ECOM = Alias.of(ECOM, "cust-002");

    protected EntityStorage storage;

    /**
     * Returns a fresh, empty {@link EntityStorage} for the test about to run.
     * Implementations using shared infrastructure (database containers,
     * collections) must isolate state — e.g. by dropping/recreating the schema
     * or scoping each storage to its own collection name.
     */
    protected abstract EntityStorage newStorage();

    @BeforeEach
    final void initStorage() {
        storage = newStorage();
    }

    // ── findByUrn ──────────────────────────────────────────────────────────

    @Test
    void findByUrn_emptyStorage_returnsEmpty() {
        assertThat(storage.findByUrn(freshEntityId())).isEmpty();
    }

    @Test
    void findByUrn_afterMint_returnsStoredEntity() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        assertThat(storage.findByUrn(alice.id())).contains(alice);
    }

    @Test
    void findByUrn_unknownUrn_returnsEmpty() {
        storage.resolveOrCreate(ALICE_ECOM, () -> entityWith(0, List.of(ALICE_ECOM)));

        assertThat(storage.findByUrn(freshEntityId())).isEmpty();
    }

    // ── findByHumanId ──────────────────────────────────────────────────────

    @Test
    void findByHumanId_emptyStorage_returnsEmpty() {
        assertThat(storage.findByHumanId(HumanIdGenerator.format(0L))).isEmpty();
    }

    @Test
    void findByHumanId_afterMint_returnsStoredEntity() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        assertThat(storage.findByHumanId(alice.humanId())).contains(alice);
    }

    @Test
    void findByHumanId_unknownHumanId_returnsEmpty() {
        storage.resolveOrCreate(ALICE_ECOM, () -> entityWith(0, List.of(ALICE_ECOM)));

        assertThat(storage.findByHumanId(HumanIdGenerator.format(999_999L))).isEmpty();
    }

    // ── findByAlias ────────────────────────────────────────────────────────

    @Test
    void findByAlias_emptyStorage_returnsEmpty() {
        assertThat(storage.findByAlias(ALICE_ECOM)).isEmpty();
    }

    @Test
    void findByAlias_afterMint_returnsStoredEntity() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        assertThat(storage.findByAlias(ALICE_ECOM)).contains(alice);
    }

    @Test
    void findByAlias_afterAddAlias_lookupSucceedsForBothAliases() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        Entity updated = storage.addAlias(alice.id(), ALICE_POS);

        assertThat(storage.findByAlias(ALICE_ECOM)).contains(updated);
        assertThat(storage.findByAlias(ALICE_POS)).contains(updated);
    }

    @Test
    void findByAlias_unknownAlias_returnsEmpty() {
        storage.resolveOrCreate(ALICE_ECOM, () -> entityWith(0, List.of(ALICE_ECOM)));

        assertThat(storage.findByAlias(BOB_ECOM)).isEmpty();
    }

    // ── resolveOrCreate ───────────────────────────────────────────────────

    @Test
    void resolveOrCreate_freshAlias_returnsCreatedAndPersistsAcrossAllIndexes() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM));

        StorageOutcome outcome = storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        assertThat(outcome).isInstanceOf(StorageOutcome.Created.class);
        assertThat(outcome.entity()).isEqualTo(alice);
        assertThat(storage.findByUrn(alice.id())).contains(alice);
        assertThat(storage.findByHumanId(alice.humanId())).contains(alice);
        assertThat(storage.findByAlias(ALICE_ECOM)).contains(alice);
    }

    @Test
    void resolveOrCreate_existingAlias_returnsExistingAndDoesNotInvokeFactory() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        boolean[] factoryCalled = {false};
        StorageOutcome outcome = storage.resolveOrCreate(ALICE_ECOM, () -> {
            factoryCalled[0] = true;
            return entityWith(1, List.of(ALICE_ECOM));
        });

        assertThat(outcome).isInstanceOf(StorageOutcome.Existing.class);
        assertThat(outcome.entity()).isEqualTo(alice);
        assertThat(factoryCalled[0])
                .as("factory must not be invoked on alias-hit")
                .isFalse();
    }

    @Test
    void resolveOrCreate_factoryReturningEntityWithoutClaimAlias_isRejected() {
        Entity wrong = entityWith(0, List.of(BOB_ECOM));

        assertThatThrownBy(() -> storage.resolveOrCreate(ALICE_ECOM, () -> wrong))
                .isInstanceOf(IllegalStateException.class);

        // Nothing must have been persisted.
        assertThat(storage.findByAlias(ALICE_ECOM)).isEmpty();
        assertThat(storage.findByAlias(BOB_ECOM)).isEmpty();
        assertThat(storage.findByUrn(wrong.id())).isEmpty();
    }

    @Test
    void resolveOrCreate_humanIdCollision_isRejectedAndNothingPersisted() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        Entity colliding = new Entity(
                EntityId.of(UUID.randomUUID()),
                alice.humanId(),
                List.of(BOB_ECOM),
                List.of(MatchingAttribute.of("email", "bob@example.com")),
                null,
                NOW,
                NOW
        );

        assertThatThrownBy(() -> storage.resolveOrCreate(BOB_ECOM, () -> colliding))
                .isInstanceOf(RuntimeException.class);

        assertThat(storage.findByAlias(BOB_ECOM)).isEmpty();
        assertThat(storage.findByUrn(colliding.id())).isEmpty();
    }

    @Test
    void resolveOrCreate_additionalAliasAlreadyClaimed_isRejected() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        // bob is being minted under bobEcom, but tries to also claim aliceEcom.
        Entity bob = entityWith(1, List.of(BOB_ECOM, ALICE_ECOM));

        assertThatThrownBy(() -> storage.resolveOrCreate(BOB_ECOM, () -> bob))
                .isInstanceOf(AliasAlreadyClaimedException.class);

        // Storage must not have observably-published the partial mint.
        assertThat(storage.findByAlias(BOB_ECOM)).isEmpty();
        assertThat(storage.findByUrn(bob.id())).isEmpty();
        // Alice's original alias mapping is unchanged.
        assertThat(storage.findByAlias(ALICE_ECOM)).contains(alice);
    }

    @Test
    void resolveOrCreate_freshAliasButFactoryMintsMultipleAliases_allAliasesQueryable() {
        Entity multi = entityWith(0, List.of(ALICE_ECOM, ALICE_POS, ALICE_CRM));

        StorageOutcome outcome = storage.resolveOrCreate(ALICE_ECOM, () -> multi);

        assertThat(outcome).isInstanceOf(StorageOutcome.Created.class);
        assertThat(storage.findByAlias(ALICE_ECOM)).contains(multi);
        assertThat(storage.findByAlias(ALICE_POS)).contains(multi);
        assertThat(storage.findByAlias(ALICE_CRM)).contains(multi);
    }

    // ── addAlias ───────────────────────────────────────────────────────────

    @Test
    void addAlias_attachesAliasToExistingEntity() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        Entity updated = storage.addAlias(alice.id(), ALICE_POS);

        assertThat(updated.aliases()).containsExactlyInAnyOrder(ALICE_ECOM, ALICE_POS);
        assertThat(storage.findByAlias(ALICE_POS)).contains(updated);
        assertThat(storage.findByUrn(alice.id())).contains(updated);
    }

    @Test
    void addAlias_idempotentWhenAliasAlreadyOnEntity() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        Entity once = storage.addAlias(alice.id(), ALICE_POS);
        Entity twice = storage.addAlias(alice.id(), ALICE_POS);

        assertThat(twice.aliases()).containsExactlyInAnyOrder(ALICE_ECOM, ALICE_POS);
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void addAlias_originalClaimAliasIsIdempotent() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        Entity unchanged = storage.addAlias(alice.id(), ALICE_ECOM);

        assertThat(unchanged.aliases()).containsExactly(ALICE_ECOM);
    }

    @Test
    void addAlias_aliasOwnedByDifferentEntity_throwsAliasAlreadyClaimed() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM));
        Entity bob = entityWith(1, List.of(BOB_ECOM));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);
        storage.resolveOrCreate(BOB_ECOM, () -> bob);

        assertThatThrownBy(() -> storage.addAlias(bob.id(), ALICE_ECOM))
                .isInstanceOf(AliasAlreadyClaimedException.class);

        // Bob still resolves to one alias only; Alice still owns aliceEcom.
        assertThat(storage.findByUrn(bob.id()).orElseThrow().aliases())
                .containsExactly(BOB_ECOM);
        assertThat(storage.findByAlias(ALICE_ECOM)).contains(alice);
    }

    @Test
    void addAlias_unknownUrn_throwsEntityNotFound() {
        EntityId nowhere = freshEntityId();

        assertThatThrownBy(() -> storage.addAlias(nowhere, ALICE_ECOM))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(storage.findByAlias(ALICE_ECOM)).isEmpty();
    }

    // ── findCandidates ─────────────────────────────────────────────────────

    @Test
    void findCandidates_emptyStorage_returnsEmptySet() {
        Claim claim = claim(ALICE_ECOM, attr("email", "alice@example.com"));
        assertThat(storage.findCandidates(claim)).isEmpty();
    }

    @Test
    void findCandidates_noAliasOrAttributeOverlap_returnsEmptySet() {
        storage.resolveOrCreate(ALICE_ECOM,
                () -> entityWith(0, List.of(ALICE_ECOM),
                        List.of(attr("email", "alice@example.com"))));

        Claim unrelated = claim(BOB_ECOM, attr("email", "bob@example.com"));
        assertThat(storage.findCandidates(unrelated)).isEmpty();
    }

    @Test
    void findCandidates_singleAliasMatch_returnsThatEntity() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM),
                List.of(attr("email", "alice@example.com")));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        Claim claim = claim(ALICE_ECOM, attr("anything", "else"));
        assertThat(idsOf(storage.findCandidates(claim))).containsExactly(alice.id());
    }

    @Test
    void findCandidates_singleAttributeMatch_returnsThatEntity() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM),
                List.of(attr("email", "alice@example.com")));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        // Different alias, but the email attribute matches the stored one.
        Claim claim = claim(BOB_ECOM, attr("email", "alice@example.com"));
        assertThat(idsOf(storage.findCandidates(claim))).containsExactly(alice.id());
    }

    @Test
    void findCandidates_aliasAndAttributeBothMatchSameEntity_returnsItOnce() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM),
                List.of(attr("email", "alice@example.com")));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        Claim claim = claim(ALICE_ECOM, attr("email", "alice@example.com"));
        Set<Entity> candidates = storage.findCandidates(claim);
        assertThat(candidates).hasSize(1);
        assertThat(idsOf(candidates)).containsExactly(alice.id());
    }

    @Test
    void findCandidates_multipleEntitiesShareAttributeWithClaim_allReturned() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM),
                List.of(attr("phone", "+44 7700 900100")));
        Entity bob = entityWith(1, List.of(BOB_ECOM),
                List.of(attr("phone", "+44 7700 900100")));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);
        storage.resolveOrCreate(BOB_ECOM, () -> bob);

        // The claim's alias doesn't exist; only the phone attribute connects.
        Claim claim = claim(Alias.of(ECOM, "cust-999"), attr("phone", "+44 7700 900100"));
        assertThat(idsOf(storage.findCandidates(claim)))
                .containsExactlyInAnyOrder(alice.id(), bob.id());
    }

    @Test
    void findCandidates_unionAcrossMechanisms_returnsBoth() {
        Entity aliceByAlias = entityWith(0, List.of(ALICE_ECOM),
                List.of(attr("email", "alice@example.com")));
        Entity bobByAttribute = entityWith(1, List.of(BOB_ECOM),
                List.of(attr("phone", "+44 7700 900100")));
        storage.resolveOrCreate(ALICE_ECOM, () -> aliceByAlias);
        storage.resolveOrCreate(BOB_ECOM, () -> bobByAttribute);

        // Claim matches alice via alias and bob via attribute.
        Claim claim = claim(ALICE_ECOM, attr("phone", "+44 7700 900100"));
        assertThat(idsOf(storage.findCandidates(claim)))
                .containsExactlyInAnyOrder(aliceByAlias.id(), bobByAttribute.id());
    }

    @Test
    void findCandidates_returnsDistinctEntitiesWhenSeveralAttributesPointAtSameEntity() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM),
                List.of(attr("email", "alice@example.com"),
                        attr("phone", "+44 7700 900100")));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        Claim claim = claim(BOB_ECOM,
                attr("email", "alice@example.com"),
                attr("phone", "+44 7700 900100"));
        assertThat(storage.findCandidates(claim)).hasSize(1);
        assertThat(idsOf(storage.findCandidates(claim))).containsExactly(alice.id());
    }

    @Test
    void findCandidates_attributeNameMatchesButValueDiffers_notReturned() {
        // Stored entity has email=alice@example.com; claim has email=bob@example.com.
        // Only one attribute name; values differ. Not a candidate.
        Entity alice = entityWith(0, List.of(ALICE_ECOM),
                List.of(attr("email", "alice@example.com")));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        Claim claim = claim(BOB_ECOM, attr("email", "bob@example.com"));
        assertThat(storage.findCandidates(claim)).isEmpty();
    }

    @Test
    void findCandidates_attributeValueMatchesButNameDiffers_notReturned() {
        // Stored entity has last_name="Smith"; claim has first_name="Smith".
        // Value collides but the attribute name doesn't — not a candidate.
        Entity alice = entityWith(0, List.of(ALICE_ECOM),
                List.of(attr("last_name", "Smith")));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        Claim claim = claim(BOB_ECOM, attr("first_name", "Smith"));
        assertThat(storage.findCandidates(claim)).isEmpty();
    }

    @Test
    void findCandidates_claimWithNoAttributes_stillResolvesAliasMatches() {
        Entity alice = entityWith(0, List.of(ALICE_ECOM),
                List.of(attr("email", "alice@example.com")));
        storage.resolveOrCreate(ALICE_ECOM, () -> alice);

        Claim claimNoAttrs = new Claim(ALICE_ECOM.source(), ALICE_ECOM.sourceId(), List.of());
        assertThat(idsOf(storage.findCandidates(claimNoAttrs))).containsExactly(alice.id());
    }

    // ── Concurrency ────────────────────────────────────────────────────────

    @Test
    void resolveOrCreate_concurrentCallsForSameAlias_produceExactlyOneEntity() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch fire = new CountDownLatch(1);
            ConcurrentHashMap<EntityId, Integer> mintedIds = new ConcurrentHashMap<>();
            AtomicInteger created = new AtomicInteger();
            AtomicInteger existing = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            List<Throwable> failures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.submit(() -> {
                    Entity candidate = entityWith(index, List.of(ALICE_ECOM));
                    ready.countDown();
                    try {
                        fire.await();
                        StorageOutcome outcome = storage.resolveOrCreate(
                                ALICE_ECOM, () -> candidate);
                        mintedIds.put(outcome.entity().id(), index);
                        switch (outcome) {
                            case StorageOutcome.Created __ -> created.incrementAndGet();
                            case StorageOutcome.Existing __ -> existing.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                        synchronized (failures) {
                            failures.add(t);
                        }
                    }
                });
            }

            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("all threads should reach the start gate")
                    .isTrue();
            fire.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS))
                    .as("all threads should complete within the timeout")
                    .isTrue();

            assertThat(failures).as("unexpected errors: %s", failures).isEmpty();
            assertThat(errors.get()).isZero();
            assertThat(created.get()).as("exactly one thread mints").isEqualTo(1);
            assertThat(existing.get())
                    .as("the other threads observe the same entity")
                    .isEqualTo(threads - 1);
            assertThat(mintedIds.keySet())
                    .as("every outcome refers to the same entity URN")
                    .hasSize(1);
            assertThat(storage.findByAlias(ALICE_ECOM)).isPresent();
        } finally {
            if (!pool.isShutdown()) {
                pool.shutdownNow();
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Fixed test timestamp, millisecond-truncated so round-trips through BSON / timestamptz are stable. */
    protected static final Instant NOW = Instant.parse("2026-05-12T10:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    /**
     * Builds a deterministic Entity carrying the given aliases. Each call uses
     * a distinct seed so URN, humanId and the email attribute are unique per
     * invocation (within the seed namespace).
     */
    protected static Entity entityWith(int seed, List<Alias> aliases) {
        return entityWith(seed, aliases, List.of(MatchingAttribute.of("seed", seed)));
    }

    /** Variant of {@link #entityWith(int, List)} taking an explicit attribute list. */
    protected static Entity entityWith(int seed, List<Alias> aliases, List<MatchingAttribute> attributes) {
        UUID id = UUID.fromString("00000000-0000-7000-8000-" + String.format("%012d", seed));
        String humanId = HumanIdGenerator.format(((long) seed) & ((1L << 40) - 1L));
        return new Entity(
                EntityId.of(id),
                humanId,
                new ArrayList<>(aliases),
                attributes,
                null,
                NOW,
                NOW
        );
    }

    /** Convenience constructor for {@link MatchingAttribute}. */
    protected static MatchingAttribute attr(String name, Object value) {
        return MatchingAttribute.of(name, value);
    }

    /** Builds a {@link Claim} for the alias and (possibly zero) attributes. */
    protected static Claim claim(Alias alias, MatchingAttribute... attributes) {
        return new Claim(alias.source(), alias.sourceId(), Arrays.asList(attributes));
    }

    /** Extracts entity URNs from a set, preserving iteration order. */
    protected static List<EntityId> idsOf(Set<Entity> entities) {
        return entities.stream().map(Entity::id).collect(Collectors.toList());
    }

    /** Mints a unique EntityId not produced by {@link #entityWith}. */
    protected static EntityId freshEntityId() {
        return EntityId.of(UUID.randomUUID());
    }

    /**
     * Returns the set of distinct entity URNs reachable via {@code findByAlias}
     * over the supplied aliases. Used by adapter-level corpus contracts to
     * derive a portable entity count without leaking {@code size()} into the
     * storage port.
     */
    protected Set<EntityId> distinctEntities(Alias... aliases) {
        Set<EntityId> ids = new HashSet<>();
        for (Alias alias : Arrays.asList(aliases)) {
            storage.findByAlias(alias).ifPresent(e -> ids.add(e.id()));
        }
        return ids;
    }
}
