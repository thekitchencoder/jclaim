package uk.codery.jclaim.examples;

import uk.codery.jclaim.event.ConflictEventSink;
import uk.codery.jclaim.event.EntityAttributesConflicted;
import uk.codery.jclaim.model.Alias;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.Entity;
import uk.codery.jclaim.model.EntityId;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.ResolutionResult;
import uk.codery.jclaim.property.PropertyFixtures;
import uk.codery.jclaim.resolver.DefaultEntityResolver;
import uk.codery.jclaim.resolver.EntityResolver;
import uk.codery.jclaim.storage.memory.InMemoryEntityStorage;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runnable demonstration of {@code resolveOrMint} against a curated slice of
 * the UK property synthetic dataset. Loads five real-world properties
 * ({@code prop-001..prop-003} plus the first two flats from the converted
 * Victorian house at 23 High Street), folds their source-system records
 * into the resolver one alias at a time, and prints the resulting entity
 * graph.
 *
 * <p>The five properties were chosen to make every interesting
 * reconciliation shape visible:
 * <ul>
 *   <li>{@code prop-001} — known to all four source systems,</li>
 *   <li>{@code prop-002} — three sources (no Land Registry entry),</li>
 *   <li>{@code prop-003} — two sources (basic dwelling),</li>
 *   <li>{@code prop-004} — flat 1 of three at 23 High Street,</li>
 *   <li>{@code prop-005} — flat 2 of three, distinct entity from flat 1.</li>
 * </ul>
 *
 * <p>Output goes to {@code stdout} and is structured for human reading —
 * intended to be quotable in the project README.
 *
 * <p>Run from the project root:
 * <pre>{@code
 *   mvn -q test-compile exec:java \
 *       -Dexec.mainClass=uk.codery.jclaim.examples.PropertyQuickStart \
 *       -Dexec.classpathScope=test
 * }</pre>
 */
public final class PropertyQuickStart {

    private static final List<String> CURATED_PROPERTIES = List.of(
            "prop-001", "prop-002", "prop-003", "prop-004", "prop-005");

    private PropertyQuickStart() {
    }

    public static void main(String[] args) {
        run(System.out);
    }

    /** Runs the demo, writing the entity graph to {@code out}. Returns the graph for inspection. */
    public static Map<String, Entity> run(PrintStream out) {
        PropertyFixtures fixtures = PropertyFixtures.load();
        EntityResolver resolver = DefaultEntityResolver.builder(new InMemoryEntityStorage())
                .namespace("codery")
                .conflictSink(loggingConflictSink(out))
                .build();

        header(out);

        Map<String, Entity> entityByProperty = new LinkedHashMap<>();
        for (String propertyId : CURATED_PROPERTIES) {
            List<Claim> claims = fixtures.claimsFor(propertyId);
            Entity entity = ingestOneProperty(resolver, propertyId, claims, out);
            entityByProperty.put(propertyId, entity);
        }

        out.println();
        out.println("---- Final entity graph ----");
        out.println();
        for (Map.Entry<String, Entity> entry : entityByProperty.entrySet()) {
            printEntity(out, entry.getKey(), entry.getValue());
        }
        return entityByProperty;
    }

    private static Entity ingestOneProperty(
            EntityResolver resolver,
            String propertyId,
            List<Claim> claims,
            PrintStream out) {
        out.println();
        out.println(propertyId + " -- " + claims.size() + " source record(s)");

        Entity entity = null;
        for (Claim claim : claims) {
            String aliasLabel = claim.source().name() + "/" + claim.sourceId();
            if (entity == null) {
                ResolutionResult result = resolver.resolveOrMint(claim);
                entity = result.entity();
                out.printf("  resolveOrMint %-44s -> %s  %s%n",
                        aliasLabel,
                        result instanceof ResolutionResult.Minted ? "Minted " : "Matched",
                        entity.id().urn());
            } else {
                Entity updated = resolver.addAlias(
                        entity.id(), claim.source(), claim.sourceId());
                entity = updated;
                out.printf("  addAlias      %-44s -> attached%n", aliasLabel);
            }
        }
        return entity;
    }

    private static void printEntity(PrintStream out, String propertyId, Entity entity) {
        out.println(propertyId);
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
        out.println("JClaim -- UK property reconciliation");
        out.println("====================================");
        out.println();
        out.println("Each property arrives as one or more source-system claims.");
        out.println("The first claim mints a canonical entity; subsequent claims");
        out.println("attach as aliases on the same entity.");
    }

    private static ConflictEventSink loggingConflictSink(PrintStream out) {
        return event -> {
            EntityId urn = event.stored().id();
            out.println("  ! conflict on " + urn + ":");
            event.differences().forEach(diff -> out.printf(
                    "      %s: stored=%s incoming=%s%n",
                    diff.name(), diff.stored(), diff.incoming()));
        };
    }
}
