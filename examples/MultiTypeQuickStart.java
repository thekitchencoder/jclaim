package uk.codery.jclaim.examples;

import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.model.SourceSystem;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.resolver.EntityResolvers;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runnable demonstration of reconciling <strong>multiple entity types</strong>
 * in one application, without Spring. Two types — {@code customer} and
 * {@code vehicle} — each get their own {@link EntityResolver} over a separate
 * {@link InMemoryEntityStorage} instance, and both are wrapped in an
 * {@link EntityResolvers} registry (the selection facade).
 *
 * <p>This mirrors what the Spring Boot starter's {@code jclaim.entity-types.<type>}
 * map builds for you: one resolver per type, distinct URN {@code <type>}
 * segments, type-specific publicId templates, and <strong>physical per-type
 * storage isolation</strong>. The demo makes the headline isolation property
 * visible: the <em>same</em> {@code (source, sourceId)} alias resolves to
 * <em>independent</em> entities under different types — the customer store and
 * the vehicle store never collide.
 *
 * <p>It also shows the per-scope publicId caveat in action: because uniqueness
 * is per storage scope, the two types use distinct, type-prefixed templates
 * ({@code CU-…} and {@code VH…}) so a displayed ID is unambiguous about which
 * type it names.
 *
 * <p>Run from the project root:
 * <pre>{@code
 *   mvn -q test-compile exec:java \
 *       -Dexec.mainClass=uk.codery.jclaim.examples.MultiTypeQuickStart \
 *       -Dexec.classpathScope=test
 * }</pre>
 */
public final class MultiTypeQuickStart {

    private static final SourceSystem CRM = SourceSystem.of("crm");
    private static final SourceSystem DVLA = SourceSystem.of("dvla");

    private MultiTypeQuickStart() {
    }

    public static void main(String[] args) {
        run(System.out);
    }

    /**
     * Runs the demo, writing the per-type entity graph to {@code out}. Returns
     * a map of {@code "<type>/<key>" -> Entity} for inspection.
     */
    public static Map<String, Entity> run(PrintStream out) {
        // One resolver per entity type, each over its OWN in-memory storage —
        // that separate storage instance IS the per-type boundary. The map key
        // is the URN <type> segment and the EntityResolvers handle.
        EntityResolver customers = DefaultEntityResolver.builder(new InMemoryEntityStorage())
                .namespace("acme")
                .entityType("customer")
                .publicIdTemplate("CU-????-????-?")
                .build();
        EntityResolver vehicles = DefaultEntityResolver.builder(new InMemoryEntityStorage())
                .namespace("acme")
                .entityType("vehicle")
                .publicIdTemplate("VH??????")
                .build();

        Map<String, EntityResolver> byType = new LinkedHashMap<>();
        byType.put("customer", customers);
        byType.put("vehicle", vehicles);
        EntityResolvers jclaim = EntityResolvers.of(byType);

        header(out, jclaim);

        Map<String, Entity> graph = new LinkedHashMap<>();

        // Reconcile two customer claims; the second adds an alias to the first.
        out.println();
        out.println("customer -- 1 customer, two source records");
        Entity alice = mint(out, jclaim, "customer",
                claim(CRM, "c-100", "name", "Alice Smith"));
        graph.put("customer/alice", alice);
        graph.put("customer/alice-merged",
                addAlias(out, jclaim, "customer", alice, SourceSystem.of("billing"), "b-77"));

        // Reconcile a vehicle claim that REUSES the customer's (source, sourceId).
        // Because the vehicle store is a separate scope, this mints a brand-new,
        // independent entity rather than matching the customer.
        out.println();
        out.println("vehicle -- same (crm, c-100) alias, different type");
        Entity car = mint(out, jclaim, "vehicle",
                claim(CRM, "c-100", "make", "Volvo"));
        graph.put("vehicle/car", car);

        // Prove isolation explicitly.
        out.println();
        out.println("---- Isolation check ----");
        out.printf("  (crm, c-100) under customer -> %s%n", alice.id().urn());
        out.printf("  (crm, c-100) under vehicle  -> %s%n", car.id().urn());
        out.printf("  independent entities? %s%n", !alice.id().equals(car.id()));

        // forType throws on an unknown type, listing the known ones.
        try {
            jclaim.forType("order");
        } catch (IllegalArgumentException expected) {
            out.println();
            out.println("  forType(\"order\") -> " + expected.getMessage());
        }

        out.println();
        out.println("---- Per-type entity graph ----");
        out.println();
        printEntity(out, "customer", alice);
        printEntity(out, "vehicle", car);

        return graph;
    }

    private static Entity mint(
            PrintStream out, EntityResolvers jclaim, String type, Claim claim) {
        ResolutionResult result = jclaim.forType(type).resolveOrMint(claim);
        Entity entity = result.entity();
        out.printf("  resolveOrMint %-18s -> %s  %s  publicId=%s%n",
                claim.source().name() + "/" + claim.sourceId(),
                result instanceof ResolutionResult.Minted ? "Minted " : "Matched",
                entity.id().urn(),
                entity.publicId());
        return entity;
    }

    private static Entity addAlias(
            PrintStream out, EntityResolvers jclaim, String type, Entity entity,
            SourceSystem source, String sourceId) {
        Entity updated = jclaim.forType(type).addAlias(entity.id(), source, sourceId);
        out.printf("  addAlias      %-18s -> attached%n", source.name() + "/" + sourceId);
        return updated;
    }

    private static Claim claim(SourceSystem source, String sourceId, String name, Object value) {
        return new Claim(source, sourceId, List.of(MatchingAttribute.of(name, value)));
    }

    private static void printEntity(PrintStream out, String type, Entity entity) {
        out.println(type);
        out.println("  urn      = " + entity.id().urn());
        out.println("  publicId = " + entity.publicId());
        out.println("  aliases  :");
        entity.aliases().forEach(alias ->
                out.println("      " + alias.source().name() + "/" + alias.sourceId()));
        out.println();
    }

    private static void header(PrintStream out, EntityResolvers jclaim) {
        out.println("JClaim -- Multiple entity types");
        out.println("===============================");
        out.println();
        out.println("Two entity types reconciled side by side, each its own resolver");
        out.println("over its own storage scope. Known types: " + jclaim.types());
        out.println("Isolation is physical per type: the same (source, sourceId) alias");
        out.println("yields independent entities under 'customer' and 'vehicle'.");
    }
}
