package uk.codery.jclaim.retail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import uk.codery.jclaim.model.Claim;
import uk.codery.jclaim.model.MatchingAttribute;
import uk.codery.jclaim.model.SourceSystem;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loader for the retail synthetic dataset under
 * {@code src/test/resources/retail-fixtures/}. Reads the YAML fixtures into
 * {@link Claim} objects and exposes the ground-truth mapping from
 * synthetic customer-id to the source-system claims that belong together.
 *
 * <p>The dataset is a fixed test asset — see
 * {@code retail-fixtures/README.md} for the layout and scenario coverage.
 */
public final class RetailFixtures {

    /** Keys reserved by the fixture schema; all other map entries become attributes. */
    private static final String SOURCE_ID_KEY = "source_id";
    private static final String NOTES_KEY = "notes";

    private static final String CUSTOMERS_RESOURCE = "retail-fixtures/customers.yaml";
    private static final String UPDATES_RESOURCE = "retail-fixtures/updates.yaml";

    private final Map<String, List<Claim>> claimsByCustomer;
    private final List<Claim> allClaims;
    private final List<Claim> updateClaims;

    private RetailFixtures(
            Map<String, List<Claim>> claimsByCustomer,
            List<Claim> allClaims,
            List<Claim> updateClaims) {
        this.claimsByCustomer = claimsByCustomer;
        this.allClaims = allClaims;
        this.updateClaims = updateClaims;
    }

    /** Loads the baked-in retail fixtures from the classpath. */
    public static RetailFixtures load() {
        Map<String, List<Claim>> byCustomer = parseCustomers(CUSTOMERS_RESOURCE);

        List<Claim> all = new ArrayList<>();
        for (List<Claim> claims : byCustomer.values()) {
            all.addAll(claims);
        }

        List<Claim> updates = parseUpdates(UPDATES_RESOURCE);

        return new RetailFixtures(
                Collections.unmodifiableMap(byCustomer),
                Collections.unmodifiableList(all),
                Collections.unmodifiableList(updates));
    }

    /** Ground-truth mapping: synthetic customer-id → claims that must reconcile together. */
    public Map<String, List<Claim>> claimsByCustomer() {
        return claimsByCustomer;
    }

    /** Flat list of every claim across every source system, suitable for ingestion. */
    public List<Claim> allClaims() {
        return allClaims;
    }

    /** Claims that re-assert an existing alias with mutated attributes (conflict scenarios). */
    public List<Claim> updateClaims() {
        return updateClaims;
    }

    /** Number of customers in the ground truth. */
    public int customerCount() {
        return claimsByCustomer.size();
    }

    /** Claims belonging to a single customer in the ground truth. */
    public List<Claim> claimsFor(String customerId) {
        List<Claim> claims = claimsByCustomer.get(customerId);
        if (claims == null) {
            throw new IllegalArgumentException("Unknown ground-truth customer: " + customerId);
        }
        return claims;
    }

    private static Map<String, List<Claim>> parseCustomers(String resource) {
        Map<String, Object> root = readYaml(resource);
        Object customersNode = Objects.requireNonNull(
                root.get("customers"), "customers.yaml: missing 'customers' key");
        if (!(customersNode instanceof List<?> customers)) {
            throw new IllegalStateException("customers.yaml: 'customers' must be a list");
        }

        Map<String, List<Claim>> byCustomer = new LinkedHashMap<>();
        for (Object item : customers) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IllegalStateException("customers.yaml: each entry must be a mapping");
            }
            String customerId = stringField(entry, "id");
            Object recordsNode = entry.get("records");
            if (!(recordsNode instanceof Map<?, ?> records)) {
                throw new IllegalStateException(
                        "customers.yaml: " + customerId + ": 'records' must be a mapping");
            }
            List<Claim> claims = new ArrayList<>(records.size());
            for (Map.Entry<?, ?> recordEntry : records.entrySet()) {
                String sourceName = String.valueOf(recordEntry.getKey());
                if (!(recordEntry.getValue() instanceof Map<?, ?> record)) {
                    throw new IllegalStateException(
                            "customers.yaml: " + customerId + ": "
                                    + sourceName + " must be a mapping");
                }
                claims.add(toClaim(sourceName, record, customerId));
            }
            if (byCustomer.put(customerId, List.copyOf(claims)) != null) {
                throw new IllegalStateException("Duplicate customer id: " + customerId);
            }
        }
        return byCustomer;
    }

    private static List<Claim> parseUpdates(String resource) {
        Map<String, Object> root = readYaml(resource);
        Object updatesNode = root.get("updates");
        if (updatesNode == null) {
            return List.of();
        }
        if (!(updatesNode instanceof List<?> updates)) {
            throw new IllegalStateException("updates.yaml: 'updates' must be a list");
        }
        List<Claim> claims = new ArrayList<>(updates.size());
        for (Object item : updates) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IllegalStateException("updates.yaml: each entry must be a mapping");
            }
            String source = stringField(entry, "source");
            String sourceId = stringField(entry, "source_id");
            Object attributesNode = entry.get("attributes");
            if (!(attributesNode instanceof Map<?, ?> attrs)) {
                throw new IllegalStateException(
                        "updates.yaml: " + sourceId + ": 'attributes' must be a mapping");
            }
            List<MatchingAttribute> attributes = toAttributes(attrs);
            claims.add(new Claim(SourceSystem.of(source), sourceId, attributes));
        }
        return claims;
    }

    private static Claim toClaim(String sourceName, Map<?, ?> record, String context) {
        Object sourceIdValue = record.get(SOURCE_ID_KEY);
        if (sourceIdValue == null) {
            throw new IllegalStateException(
                    "customers.yaml: " + context + " / " + sourceName + ": missing " + SOURCE_ID_KEY);
        }
        String sourceId = String.valueOf(sourceIdValue);

        Map<Object, Object> attrSource = new LinkedHashMap<>(record);
        attrSource.remove(SOURCE_ID_KEY);
        attrSource.remove(NOTES_KEY);
        List<MatchingAttribute> attributes = toAttributes(attrSource);

        return new Claim(SourceSystem.of(sourceName), sourceId, attributes);
    }

    private static List<MatchingAttribute> toAttributes(Map<?, ?> raw) {
        List<MatchingAttribute> attributes = new ArrayList<>(raw.size());
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            attributes.add(MatchingAttribute.of(name, value));
        }
        return attributes;
    }

    private static String stringField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing required field '" + key + "'");
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> readYaml(String resource) {
        ClassLoader cl = Objects.requireNonNullElse(
                Thread.currentThread().getContextClassLoader(),
                RetailFixtures.class.getClassLoader());
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Fixture resource not found: " + resource);
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            return mapper.readValue(in, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read " + resource, ex);
        }
    }
}
