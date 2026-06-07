package uk.codery.jclaim.examples;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.Entity;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the {@link MultiTypeQuickStart} demonstration end-to-end and asserts
 * the headline multi-type guarantees the README quotes: two independent
 * resolvers, distinct URN types, type-specific publicIds, and — the crucial
 * one — physical per-type isolation, so the same {@code (source, sourceId)}
 * alias yields independent entities under different types. Keeps the example
 * honest: any change that breaks it also breaks this test.
 */
class MultiTypeQuickStartTest {

    @Test
    void runReconcilesTwoTypesInIsolationWithTypeScopedPublicIds() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Map<String, Entity> graph = MultiTypeQuickStart.run(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        Entity customer = graph.get("customer/alice");
        Entity vehicle = graph.get("vehicle/car");

        // Distinct URN type segments under the shared namespace.
        assertThat(customer.id().urn()).startsWith("urn:acme:customer:");
        assertThat(vehicle.id().urn()).startsWith("urn:acme:vehicle:");

        // The same (crm, c-100) alias resolved to INDEPENDENT entities — the
        // physical per-type isolation guarantee.
        assertThat(customer.id()).isNotEqualTo(vehicle.id());

        // Type-specific publicId templates rendered.
        assertThat(customer.publicId()).startsWith("CU-");
        assertThat(vehicle.publicId()).startsWith("VH");

        // Second customer source attached as an alias on the same entity.
        assertThat(graph.get("customer/alice-merged").aliases()).hasSize(2);

        String output = buf.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains("JClaim -- Multiple entity types")
                .contains("Known types: [customer, vehicle]")
                .contains("independent entities? true")
                .contains("forType(\"order\")")
                .contains("urn:acme:customer:")
                .contains("urn:acme:vehicle:");
    }
}
