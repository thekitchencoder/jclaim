package uk.codery.jclaim.examples;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.Entity;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the {@link ProductQuickStart} demonstration end-to-end and asserts
 * that the printed output contains the markers any README snippet quotes.
 * Keeps the example honest: any change that breaks the example also
 * breaks this test, so any quoted snippet cannot drift out of date
 * silently.
 */
class ProductQuickStartTest {

    @Test
    void runProducesEntityForEveryCuratedProductAndAdvertisedHeaders() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Map<String, Entity> graph = ProductQuickStart.run(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        assertThat(graph).hasSize(5)
                .containsKeys("prod-001", "prod-002", "prod-003", "prod-004", "prod-005");

        // The all-four-sources hero product must end up with four aliases on
        // a single entity.
        assertThat(graph.get("prod-001").aliases()).hasSize(4);
        // The size variant remains distinct from the hero.
        assertThat(graph.get("prod-005").id()).isNotEqualTo(graph.get("prod-001").id());

        String output = buf.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains("JClaim -- Product SKU reconciliation")
                .contains("resolveOrMint")
                .contains("Minted")
                .contains("Final entity graph")
                .contains("urn:codery:entity:")
                .contains("pim/pim-00001");
    }
}
