package ai.doctruth.internal.schema;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Provider-facing schema projections. These are deliberately weaker than local
 * validation: model providers receive the subset they are likely to accept, while
 * {@link JsonSchemaValidator} remains the source of truth after the model responds.
 *
 * @hidden
 */
public final class ProviderSchemaProjection {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private ProviderSchemaProjection() {
        throw new AssertionError("no instances");
    }

    public static JsonNode forGemini(JsonNode schema) {
        return projectGemini(schema, schema);
    }

    private static JsonNode projectGemini(JsonNode schema, JsonNode root) {
        if (!schema.isObject()) {
            return schema.deepCopy();
        }
        if (schema.has("$ref")) {
            return projectGemini(resolveLocalRef(schema.path("$ref").asText(), root), root);
        }
        Optional<ObjectNode> nullableUnion = projectNullableUnion(schema, root);
        if (nullableUnion.isPresent()) {
            return nullableUnion.get();
        }
        if (schema.path("allOf").isArray()) {
            return projectAllOf(schema.path("allOf"), root);
        }
        var out = JSON.objectNode();
        copyType(schema, out);
        copyScalar("format", schema, out);
        copyScalar("description", schema, out);
        copyScalar("enum", schema, out);
        copyScalar("required", schema, out);
        copyScalar("minItems", schema, out);
        copyScalar("maxItems", schema, out);
        copyObjectProperties(schema, root, out);
        copyArrayItems(schema, root, out);
        return out;
    }

    private static JsonNode resolveLocalRef(String ref, JsonNode root) {
        if (!ref.startsWith("#/")) {
            return JSON.objectNode();
        }
        JsonNode resolved = root.at(ref.substring(1));
        return resolved.isMissingNode() ? JSON.objectNode() : resolved;
    }

    private static Optional<ObjectNode> projectNullableUnion(JsonNode schema, JsonNode root) {
        Optional<JsonNode> unionBranch = nonNullAnyOfBranch(schema);
        if (unionBranch.isEmpty()) {
            unionBranch = nonNullTypeArrayBranch(schema);
        }
        if (unionBranch.isEmpty()) {
            return Optional.empty();
        }
        var projected = (ObjectNode) projectGemini(unionBranch.get(), root);
        projected.put("nullable", true);
        return Optional.of(projected);
    }

    private static Optional<JsonNode> nonNullAnyOfBranch(JsonNode schema) {
        JsonNode anyOf = schema.path("anyOf");
        if (!anyOf.isArray() || anyOf.size() != 2) {
            return Optional.empty();
        }
        JsonNode first = anyOf.get(0);
        JsonNode second = anyOf.get(1);
        if (isNullSchema(first)) {
            return Optional.of(second);
        }
        return isNullSchema(second) ? Optional.of(first) : Optional.empty();
    }

    private static Optional<JsonNode> nonNullTypeArrayBranch(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (!type.isArray() || type.size() != 2) {
            return Optional.empty();
        }
        for (JsonNode typeValue : type) {
            if (!"null".equals(typeValue.asText())) {
                var branch = JSON.objectNode();
                branch.put("type", typeValue.asText());
                copyNonStructuralKeywords(schema, branch);
                return Optional.of(branch);
            }
        }
        return Optional.empty();
    }

    private static JsonNode projectAllOf(JsonNode branches, JsonNode root) {
        var merged = JSON.objectNode();
        for (JsonNode branch : branches) {
            mergeObject(merged, projectGemini(branch, root));
        }
        return merged;
    }

    private static void mergeObject(ObjectNode target, JsonNode source) {
        if (!source.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            target.set(field.getKey(), field.getValue().deepCopy());
        }
    }

    private static boolean isNullSchema(JsonNode schema) {
        return "null".equals(schema.path("type").asText());
    }

    private static void copyType(JsonNode schema, ObjectNode out) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) {
            out.put("type", type.asText());
        }
    }

    private static void copyScalar(String field, JsonNode schema, ObjectNode out) {
        if (schema.has(field)) {
            out.set(field, schema.get(field).deepCopy());
        }
    }

    private static void copyObjectProperties(JsonNode schema, JsonNode root, ObjectNode out) {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            return;
        }
        var projectedProperties = JSON.objectNode();
        properties
                .fields()
                .forEachRemaining(
                        entry -> projectedProperties.set(entry.getKey(), projectGemini(entry.getValue(), root)));
        out.set("properties", projectedProperties);
    }

    private static void copyArrayItems(JsonNode schema, JsonNode root, ObjectNode out) {
        if (schema.has("items")) {
            out.set("items", projectGemini(schema.get("items"), root));
        }
    }

    private static void copyNonStructuralKeywords(JsonNode schema, ObjectNode out) {
        copyScalar("format", schema, out);
        copyScalar("description", schema, out);
        copyScalar("enum", schema, out);
        copyScalar("minItems", schema, out);
        copyScalar("maxItems", schema, out);
    }
}
