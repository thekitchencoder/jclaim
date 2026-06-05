package uk.codery.jclaim.spring.metrics;

import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.EntityResolver;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-pins the {@code type} meter tag on {@link MeteredEntityResolver}: the
 * 3-arg constructor tags every meter; the 2-arg constructor leaves them untagged.
 */
class MeteredEntityResolverTest {

    private static final Claim CLAIM =
            new Claim(SourceSystem.of("crm"), "u-1", List.of());

    @Test
    void threeArgCtorTagsResolveCounterWithType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EntityResolver metered = new MeteredEntityResolver(new MintingStub(), registry, "customer");

        metered.resolveOrMint(CLAIM);
        metered.findCandidates(CLAIM);

        assertThat(registry.find("jclaim.resolve").tags("type", "customer", "outcome", "minted")
                .counter()).isNotNull();
        assertThat(registry.find("jclaim.resolve").tags("type", "customer", "outcome", "minted")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find("jclaim.resolve.duration").tag("type", "customer").timer())
                .isNotNull();
        assertThat(registry.find("jclaim.findCandidates").tag("type", "customer").counter())
                .isNotNull();
    }

    @Test
    void twoArgCtorEmitsNoTypeTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EntityResolver metered = new MeteredEntityResolver(new MintingStub(), registry);

        metered.resolveOrMint(CLAIM);

        // The untagged counter exists.
        assertThat(registry.find("jclaim.resolve").tags("outcome", "minted").counter())
                .isNotNull();
        // No series carries a "type" tag at all.
        assertThat(Search.in(registry).name("jclaim.resolve").meters())
                .allSatisfy(m -> assertThat(m.getId().getTag("type")).isNull());
    }

    @Test
    void blankTypeIsTreatedAsUntagged() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EntityResolver metered = new MeteredEntityResolver(new MintingStub(), registry, "  ");

        metered.resolveOrMint(CLAIM);

        assertThat(Search.in(registry).name("jclaim.resolve").meters())
                .allSatisfy(m -> assertThat(m.getId().getTag("type")).isNull());
    }

    @Test
    void twoArgCtorTagsFindCandidatesCounterWithoutType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EntityResolver metered = new MeteredEntityResolver(new MintingStub(), registry);

        metered.findCandidates(CLAIM);

        // The findCandidates counter is registered untagged on the 2-arg path.
        assertThat(registry.find("jclaim.findCandidates").counter()).isNotNull();
        assertThat(registry.find("jclaim.findCandidates").counter().count()).isEqualTo(1.0);
        assertThat(Search.in(registry).name("jclaim.findCandidates").meters())
                .allSatisfy(m -> assertThat(m.getId().getTag("type")).isNull());
    }

    /**
     * The non-instrumented operations forward straight to the delegate with no
     * meter side effects. A {@link RecordingStub} proves each call reaches the
     * delegate and returns the delegate's value verbatim.
     */
    @Test
    void nonInstrumentedOperationsForwardToDelegate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingStub stub = new RecordingStub();
        EntityResolver metered = new MeteredEntityResolver(stub, registry, "customer");

        EntityId urn = EntityId.of("acme", "customer", UUID.randomUUID());
        SourceSystem source = SourceSystem.of("crm");

        assertThat(metered.getByUrn(urn)).isSameAs(stub.entity);
        assertThat(stub.getByUrnArg).isEqualTo(urn);

        assertThat(metered.findByHumanId("K7M2-9X4P-3")).contains(stub.entity);
        assertThat(stub.findByHumanIdArg).isEqualTo("K7M2-9X4P-3");

        assertThat(metered.findByAlias(source, "u-9")).contains(stub.entity);
        assertThat(stub.findByAliasSource).isEqualTo(source);
        assertThat(stub.findByAliasSourceId).isEqualTo("u-9");

        assertThat(metered.addAlias(urn, source, "u-9")).isSameAs(stub.entity);
        assertThat(stub.addAliasUrn).isEqualTo(urn);

        // None of these forwarding calls touched the meter registry.
        assertThat(registry.getMeters())
                .noneMatch(m -> m.getId().getName().equals("jclaim.resolve")
                        || m.getId().getName().equals("jclaim.findCandidates"));
    }

    /** Always mints, so resolveOrMint yields a {@code Minted} outcome. */
    private static final class MintingStub implements EntityResolver {
        @Override
        public ResolutionResult resolveOrMint(Claim claim) {
            Instant now = Instant.now();
            Entity e = new Entity(
                    EntityId.of("acme", "customer", UUID.randomUUID()),
                    null, List.of(), List.of(), null, now, now);
            return new ResolutionResult.Minted(e);
        }

        @Override public Entity getByUrn(EntityId urn) { throw new UnsupportedOperationException(); }
        @Override public Optional<Entity> findByHumanId(String humanId) { return Optional.empty(); }
        @Override public Optional<Entity> findByAlias(SourceSystem source, String sourceId) { return Optional.empty(); }
        @Override public Entity addAlias(EntityId urn, SourceSystem source, String sourceId) { throw new UnsupportedOperationException(); }
        @Override public Set<Entity> findCandidates(Claim claim) { return Set.of(); }
    }

    /** Records every forwarded call and returns a fixed entity, so delegation is observable. */
    private static final class RecordingStub implements EntityResolver {
        final Entity entity;
        EntityId getByUrnArg;
        String findByHumanIdArg;
        SourceSystem findByAliasSource;
        String findByAliasSourceId;
        EntityId addAliasUrn;

        RecordingStub() {
            Instant now = Instant.now();
            this.entity = new Entity(
                    EntityId.of("acme", "customer", UUID.randomUUID()),
                    null, List.of(), List.of(), null, now, now);
        }

        @Override public ResolutionResult resolveOrMint(Claim claim) { return new ResolutionResult.Minted(entity); }
        @Override public Entity getByUrn(EntityId urn) { this.getByUrnArg = urn; return entity; }
        @Override public Optional<Entity> findByHumanId(String humanId) { this.findByHumanIdArg = humanId; return Optional.of(entity); }
        @Override public Optional<Entity> findByAlias(SourceSystem source, String sourceId) {
            this.findByAliasSource = source; this.findByAliasSourceId = sourceId; return Optional.of(entity);
        }
        @Override public Entity addAlias(EntityId urn, SourceSystem source, String sourceId) { this.addAliasUrn = urn; return entity; }
        @Override public Set<Entity> findCandidates(Claim claim) { return Set.of(); }
    }
}
