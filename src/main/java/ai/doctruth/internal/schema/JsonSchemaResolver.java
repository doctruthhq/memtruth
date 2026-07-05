package ai.doctruth.internal.schema;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Resolves the local JSON Schema reference subset DocTruth accepts at extraction
 * boundaries.
 *
 * @hidden
 */
public final class JsonSchemaResolver {

    private JsonSchemaResolver() {
        throw new AssertionError("no instances");
    }

    public static JsonNode resolveLocal(JsonNode schema, JsonNode root) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(root, "root");
        return resolveLocal(schema, root, new LinkedHashSet<>());
    }

    private static JsonNode resolveLocal(JsonNode schema, JsonNode root, Set<String> seenRefs) {
        if (!schema.has("$ref")) {
            return schema;
        }
        String ref = schema.path("$ref").asText();
        if (!ref.startsWith("#/")) {
            return schema;
        }
        if (!seenRefs.add(ref)) {
            return schema;
        }
        JsonNode resolved = root.at(ref.substring(1));
        if (resolved.isMissingNode()) {
            return schema;
        }
        return resolveLocal(resolved, root, seenRefs);
    }
}
