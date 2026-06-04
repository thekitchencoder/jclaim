package uk.codery.jclaim.examples;

import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.event.MatchEventSink;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.retail.RetailFixtures;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runnable demonstration of {@code resolveOrMint} against a curated slice of
 * the retail synthetic dataset. Loads five real-world customers
 * ({@code cust-001..cust-005}), folds their source-system records into the
 * resolver one alias at a time, and prints the resulting entity graph.
 *
 * <p>The five customers were chosen to make every interesting reconciliation
 * shape visible:
 * <ul>
 *   <li>{@code cust-001} — known to all four source systems,</li>
 *   <li>{@code cust-002} — three sources (no in-store record),</li>
 *   <li>{@code cust-003} — two sources with phone-format variation,</li>
 *   <li>{@code cust-004} — pos + loyalty link via loyalty number,</li>
 *   <li>{@code cust-005} — single-source customer.</li>
 * </ul>
 *
 * <p>Output goes to {@code stdout} and is structured for human reading —
 * intended to be quotable in the project README's Quick Start section.
 *
 * <p>Run from the project root:
 * <pre>{@code
 *   mvn -q test-compile exec:java \
 *       -Dexec.mainClass=uk.codery.jclaim.examples.RetailQuickStart \
 *       -Dexec.classpathScope=test
 * }</pre>
 */
public final class RetailQuickStart {

    private static final List<String> CURATED_CUSTOMERS = List.of(
            "cust-001", "cust-002", "cust-003", "cust-004", "cust-005");

    private RetailQuickStart() {
    }

    public static void main(String[] args) {
        run(System.out);
    }

    /** Runs the demo, writing the entity graph to {@code out}. Returns the graph for inspection. */
    public static Map<String, Entity> run(PrintStream out) {
        RetailFixtures fixtures = RetailFixtures.load();
        // Customers are a natural fit for human-typed lookup IDs, so this
        // resolver opts in to humanIds by configuring a template. Each minted
        // entity then carries a Crockford+Damm display ID (e.g. K7M2-9X4P-3).
        EntityResolver resolver = DefaultEntityResolver.builder(new InMemoryEntityStorage())
                .namespace("codery")
                .humanIdTemplate("????-????-?")
                .matchEventSink(loggingMatchSink(out))
                .build();

        header(out);

        Map<String, Entity> entityByCustomer = new LinkedHashMap<>();
        for (String customerId : CURATED_CUSTOMERS) {
            List<Claim> claims = fixtures.claimsFor(customerId);
            Entity entity = ingestOneCustomer(resolver, customerId, claims, out);
            entityByCustomer.put(customerId, entity);
        }

        out.println();
        out.println("---- Final entity graph ----");
        out.println();
        for (Map.Entry<String, Entity> entry : entityByCustomer.entrySet()) {
            printEntity(out, entry.getKey(), entry.getValue());
        }
        return entityByCustomer;
    }

    private static Entity ingestOneCustomer(
            EntityResolver resolver,
            String customerId,
            List<Claim> claims,
            PrintStream out) {
        out.println();
        out.println(customerId + " -- " + claims.size() + " source record(s)");

        Entity entity = null;
        for (Claim claim : claims) {
            String aliasLabel = claim.source().name() + "/" + claim.sourceId();
            if (entity == null) {
                ResolutionResult result = resolver.resolveOrMint(claim);
                entity = result.entity();
                out.printf("  resolveOrMint %-28s -> %s  %s%n",
                        aliasLabel,
                        result instanceof ResolutionResult.Minted ? "Minted " : "Matched",
                        entity.id().urn());
            } else {
                Entity updated = resolver.addAlias(
                        entity.id(), claim.source(), claim.sourceId());
                entity = updated;
                out.printf("  addAlias      %-28s -> attached%n", aliasLabel);
            }
        }
        return entity;
    }

    private static void printEntity(PrintStream out, String customerId, Entity entity) {
        out.println(customerId);
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
        out.println("JClaim -- Retail customer reconciliation");
        out.println("========================================");
        out.println();
        out.println("Each customer arrives as one or more source-system claims.");
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
