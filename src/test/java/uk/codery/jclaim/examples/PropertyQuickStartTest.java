package uk.codery.jclaim.examples;

import org.junit.jupiter.api.Test;
import uk.codery.jclaim.model.Entity;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the {@link PropertyQuickStart} demonstration end-to-end and asserts
 * that the printed output contains the markers any README snippet quotes.
 * Keeps the example honest: any change that breaks the example also
 * breaks this test, so any quoted snippet cannot drift out of date
 * silently.
 */
class PropertyQuickStartTest {

    @Test
    void runProducesEntityForEveryCuratedPropertyAndAdvertisedHeaders() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Map<String, Entity> graph = PropertyQuickStart.run(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        assertThat(graph).hasSize(5)
                .containsKeys("prop-001", "prop-002", "prop-003", "prop-004", "prop-005");

        // The all-four-sources hero property must end up with four aliases on
        // a single entity.
        assertThat(graph.get("prop-001").aliases()).hasSize(4);
        // The two flats in the same building remain distinct.
        assertThat(graph.get("prop-004").id()).isNotEqualTo(graph.get("prop-005").id());

        String output = buf.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains("JClaim -- UK property reconciliation")
                .contains("resolveOrMint")
                .contains("Minted")
                .contains("Final entity graph")
                .contains("urn:codery:entity:")
                .contains("royal_mail_paf/paf-100099000001");
    }
}
