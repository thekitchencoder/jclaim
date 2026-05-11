package uk.codery.jclaim.examples;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.Entity;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the {@link RetailQuickStart} demonstration end-to-end and asserts
 * that the printed output contains the markers the README quotes. Keeps
 * the example honest: any change that breaks the example also breaks this
 * test, so the README snippet never drifts out of date silently.
 */
class RetailQuickStartTest {

    @Test
    void runProducesEntityForEveryCuratedCustomerAndAdvertisedHeaders() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Map<String, Entity> graph = RetailQuickStart.run(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        assertThat(graph).hasSize(5)
                .containsKeys("cust-001", "cust-002", "cust-003", "cust-004", "cust-005");

        // The all-four-sources customer must end up with four aliases on
        // a single entity.
        assertThat(graph.get("cust-001").aliases()).hasSize(4);

        String output = buf.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains("JClaim -- Retail customer reconciliation")
                .contains("resolveOrMint")
                .contains("Minted")
                .contains("Final entity graph")
                .contains("urn:codery:entity:")
                .contains("ecommerce/ec-12345");
    }
}
