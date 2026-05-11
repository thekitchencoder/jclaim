package uk.codery.jclaim.fixtures;

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
 * Generic loader for the YAML corpora that drive JClaim's integration
 * tests. Each corpus lives under {@code src/test/resources/<directory>/}
 * with two files:
 *
 * <ul>
 *   <li>{@code <directory>/<entitiesFile>.yaml} keyed by
 *       {@code groundTruthKey} at top level — a list of ground-truth
 *       entities, each carrying a synthetic {@code id} and a
 *       {@code records} map of source-name → source-system record.</li>
 *   <li>{@code <directory>/updates.yaml} keyed by {@code updates} at top
 *       level — re-asserted aliases with mutated attributes used to
 *       exercise the conflict path.</li>
 * </ul>
 *
 * <p>The retail, product, and property corpora share this exact shape;
 * domain-specific wrappers (e.g. {@code RetailFixtures}) layer
 * readability aliases on top of this class.
 */
public final class EntityFixtures {

    /** Keys reserved by the corpus schema; all other map entries become attributes. */
    private static final String SOURCE_ID_KEY = "source_id";
    private static final String NOTES_KEY = "notes";

    private final Map<String, List<Claim>> claimsById;
    private final List<Claim> allClaims;
    private final List<Claim> updateClaims;

    private EntityFixtures(
            Map<String, List<Claim>> claimsById,
            List<Claim> allClaims,
            List<Claim> updateClaims) {
        this.claimsById = claimsById;
        this.allClaims = allClaims;
        this.updateClaims = updateClaims;
    }

    /**
     * Loads a corpus from the classpath.
     *
     * @param directory       resource directory containing the YAML files
     *                        (e.g. {@code "retail-fixtures"}).
     * @param entitiesFile    file name without extension for the
     *                        ground-truth file (e.g. {@code "customers"});
     *                        used both as the resource basename and as the
     *                        top-level YAML key.
     * @param updatesFile     file name without extension for the conflict
     *                        re-assertions, also doubling as the top-level
     *                        key in that file (typically {@code "updates"}).
     */
    public static EntityFixtures load(String directory, String entitiesFile, String updatesFile) {
        String entitiesResource = directory + "/" + entitiesFile + ".yaml";
        String updatesResource = directory + "/" + updatesFile + ".yaml";

        Map<String, List<Claim>> byId = parseEntities(entitiesResource, entitiesFile);

        List<Claim> all = new ArrayList<>();
        for (List<Claim> claims : byId.values()) {
            all.addAll(claims);
        }

        List<Claim> updates = parseUpdates(updatesResource, updatesFile);

        return new EntityFixtures(
                Collections.unmodifiableMap(byId),
                Collections.unmodifiableList(all),
                Collections.unmodifiableList(updates));
    }

    /** Convenience: loads with the conventional {@code "updates"} updates file. */
    public static EntityFixtures load(String directory, String entitiesFile) {
        return load(directory, entitiesFile, "updates");
    }

    /** Ground-truth mapping: synthetic entity-id → claims that must reconcile together. */
    public Map<String, List<Claim>> claimsById() {
        return claimsById;
    }

    /** Flat list of every claim across every source system, suitable for ingestion. */
    public List<Claim> allClaims() {
        return allClaims;
    }

    /** Claims that re-assert an existing alias with mutated attributes (conflict scenarios). */
    public List<Claim> updateClaims() {
        return updateClaims;
    }

    /** Number of ground-truth entities in the corpus. */
    public int entityCount() {
        return claimsById.size();
    }

    /** Claims belonging to a single ground-truth entity. */
    public List<Claim> claimsFor(String groundTruthId) {
        List<Claim> claims = claimsById.get(groundTruthId);
        if (claims == null) {
            throw new IllegalArgumentException("Unknown ground-truth id: " + groundTruthId);
        }
        return claims;
    }

    private static Map<String, List<Claim>> parseEntities(String resource, String topKey) {
        Map<String, Object> root = readYaml(resource);
        Object listNode = Objects.requireNonNull(
                root.get(topKey), resource + ": missing '" + topKey + "' key");
        if (!(listNode instanceof List<?> entities)) {
            throw new IllegalStateException(resource + ": '" + topKey + "' must be a list");
        }

        Map<String, List<Claim>> byId = new LinkedHashMap<>();
        for (Object item : entities) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IllegalStateException(resource + ": each entry must be a mapping");
            }
            String id = stringField(entry, "id");
            Object recordsNode = entry.get("records");
            if (!(recordsNode instanceof Map<?, ?> records)) {
                throw new IllegalStateException(
                        resource + ": " + id + ": 'records' must be a mapping");
            }
            List<Claim> claims = new ArrayList<>(records.size());
            for (Map.Entry<?, ?> recordEntry : records.entrySet()) {
                String sourceName = String.valueOf(recordEntry.getKey());
                if (!(recordEntry.getValue() instanceof Map<?, ?> record)) {
                    throw new IllegalStateException(
                            resource + ": " + id + ": " + sourceName + " must be a mapping");
                }
                claims.add(toClaim(sourceName, record, resource + ": " + id));
            }
            if (byId.put(id, List.copyOf(claims)) != null) {
                throw new IllegalStateException("Duplicate ground-truth id: " + id);
            }
        }
        return byId;
    }

    private static List<Claim> parseUpdates(String resource, String topKey) {
        Map<String, Object> root = readYaml(resource);
        Object updatesNode = root.get(topKey);
        if (updatesNode == null) {
            return List.of();
        }
        if (!(updatesNode instanceof List<?> updates)) {
            throw new IllegalStateException(resource + ": '" + topKey + "' must be a list");
        }
        List<Claim> claims = new ArrayList<>(updates.size());
        for (Object item : updates) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IllegalStateException(resource + ": each entry must be a mapping");
            }
            String source = stringField(entry, "source");
            String sourceId = stringField(entry, "source_id");
            Object attributesNode = entry.get("attributes");
            if (!(attributesNode instanceof Map<?, ?> attrs)) {
                throw new IllegalStateException(
                        resource + ": " + sourceId + ": 'attributes' must be a mapping");
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
                    context + " / " + sourceName + ": missing " + SOURCE_ID_KEY);
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
                EntityFixtures.class.getClassLoader());
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
