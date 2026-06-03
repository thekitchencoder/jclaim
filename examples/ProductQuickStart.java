package uk.codery.jclaim.examples;

import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.product.ProductFixtures;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runnable demonstration of {@code resolveOrMint} against a curated slice of
 * the product synthetic dataset. Loads five real-world products
 * ({@code prod-001..prod-005}), folds their source-system records into the
 * resolver one alias at a time, and prints the resulting entity graph.
 *
 * <p>The five products were chosen to make every interesting reconciliation
 * shape visible:
 * <ul>
 *   <li>{@code prod-001} — known to all four source systems,</li>
 *   <li>{@code prod-002} — three sources (private label, no supplier),</li>
 *   <li>{@code prod-003} — two sources (pre-launch, no warehouse or marketplace),</li>
 *   <li>{@code prod-004} — SKU-only product (warehouse-only, no GTIN),</li>
 *   <li>{@code prod-005} — size variant of {@code prod-001}, distinct entity.</li>
 * </ul>
 *
 * <p>Output goes to {@code stdout} and is structured for human reading —
 * intended to be quotable in the project README.
 *
 * <p>Run from the project root:
 * <pre>{@code
 *   mvn -q test-compile exec:java \
 *       -Dexec.mainClass=uk.codery.jclaim.examples.ProductQuickStart \
 *       -Dexec.classpathScope=test
 * }</pre>
 */
public final class ProductQuickStart {

    private static final List<String> CURATED_PRODUCTS = List.of(
            "prod-001", "prod-002", "prod-003", "prod-004", "prod-005");

    private ProductQuickStart() {
    }

    public static void main(String[] args) {
        run(System.out);
    }

    /** Runs the demo, writing the entity graph to {@code out}. Returns the graph for inspection. */
    public static Map<String, Entity> run(PrintStream out) {
        ProductFixtures fixtures = ProductFixtures.load();
        EntityResolver resolver = DefaultEntityResolver.builder(new InMemoryEntityStorage())
                .namespace("codery")
                .matchEventSink(loggingMatchSink(out))
                .build();

        header(out);

        Map<String, Entity> entityByProduct = new LinkedHashMap<>();
        for (String productId : CURATED_PRODUCTS) {
            List<Claim> claims = fixtures.claimsFor(productId);
            Entity entity = ingestOneProduct(resolver, productId, claims, out);
            entityByProduct.put(productId, entity);
        }

        out.println();
        out.println("---- Final entity graph ----");
        out.println();
        for (Map.Entry<String, Entity> entry : entityByProduct.entrySet()) {
            printEntity(out, entry.getKey(), entry.getValue());
        }
        return entityByProduct;
    }

    private static Entity ingestOneProduct(
            EntityResolver resolver,
            String productId,
            List<Claim> claims,
            PrintStream out) {
        out.println();
        out.println(productId + " -- " + claims.size() + " source record(s)");

        Entity entity = null;
        for (Claim claim : claims) {
            String aliasLabel = claim.source().name() + "/" + claim.sourceId();
            if (entity == null) {
                ResolutionResult result = resolver.resolveOrMint(claim);
                entity = result.entity();
                out.printf("  resolveOrMint %-32s -> %s  %s%n",
                        aliasLabel,
                        result instanceof ResolutionResult.Minted ? "Minted " : "Matched",
                        entity.id().urn());
            } else {
                Entity updated = resolver.addAlias(
                        entity.id(), claim.source(), claim.sourceId());
                entity = updated;
                out.printf("  addAlias      %-32s -> attached%n", aliasLabel);
            }
        }
        return entity;
    }

    private static void printEntity(PrintStream out, String productId, Entity entity) {
        out.println(productId);
        out.println("  urn      = " + entity.id().urn());
        out.println("  humanId  = " + entity.humanId());
        out.println("  aliases  :");
        for (Alias alias : entity.aliases()) {
            out.println("      " + alias.source().name() + "/" + alias.sourceId());
        }
        out.println("  attributes (from first claim ingested):");
        if (entity.attributes().isEmpty()) {
            out.println("      (none)");
        } else {
            for (MatchingAttribute attribute : entity.attributes()) {
                out.println("      " + attribute.name() + " = " + attribute.value());
            }
        }
        out.println();
    }

    private static void header(PrintStream out) {
        out.println("JClaim -- Product SKU reconciliation");
        out.println("====================================");
        out.println();
        out.println("Each product arrives as one or more source-system claims.");
        out.println("The first claim mints a canonical entity; subsequent claims");
        out.println("attach as aliases on the same entity.");
    }

    private static MatchEventSink loggingMatchSink(PrintStream out) {
        return event -> {
            if (!(event instanceof EntityAttributesConflicted conflict)) {
                return;
            }
            EntityId urn = conflict.stored().id();
            out.println("  ! conflict on " + urn + ":");
            conflict.differingValues().forEach(diff -> out.printf(
                    "      %s: stored=%s incoming=%s%n",
                    diff.name(), diff.stored(), diff.incoming()));
        };
    }
}
