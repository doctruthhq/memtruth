package ai.doctruth;

import java.util.LinkedHashMap;
import java.util.Map;

import ai.doctruth.internal.schema.JsonSchemaResolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class EvidenceFirstJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvidenceFirstJson() {
        throw new AssertionError("no instances");
    }

    static JsonNode responseSchema(JsonNode schema) {
        return responseSchema(schema, schema);
    }

    private static JsonNode responseSchema(JsonNode schema, JsonNode root) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return leafSchema(schema);
        }
        JsonNode activeSchema = JsonSchemaResolver.resolveLocal(schema, root);
        if (isObjectSchema(activeSchema)) {
            ObjectNode copy = activeSchema.deepCopy();
            ObjectNode properties = MAPPER.createObjectNode();
            activeSchema
                    .path("properties")
                    .fields()
                    .forEachRemaining(e -> properties.set(e.getKey(), responseSchema(e.getValue(), root)));
            copy.set("properties", properties);
            return copy;
        }
        if (isArraySchema(activeSchema)) {
            ObjectNode copy = activeSchema.deepCopy();
            copy.set("items", responseSchema(activeSchema.path("items"), root));
            return copy;
        }
        return leafSchema(activeSchema);
    }

    static JsonNode unwrap(JsonNode evidenceNode) {
        if (isEvidenceLeaf(evidenceNode)) {
            return evidenceNode.path("value").deepCopy();
        }
        if (evidenceNode != null && evidenceNode.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            evidenceNode.fields().forEachRemaining(e -> out.set(e.getKey(), unwrap(e.getValue())));
            return out;
        }
        if (evidenceNode != null && evidenceNode.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            evidenceNode.forEach(item -> out.add(unwrap(item)));
            return out;
        }
        return evidenceNode == null ? MAPPER.nullNode() : evidenceNode.deepCopy();
    }

    static JsonNode unwrap(JsonNode evidenceNode, JsonNode schema) {
        return unwrap(evidenceNode, schema, schema);
    }

    private static JsonNode unwrap(JsonNode evidenceNode, JsonNode schema, JsonNode root) {
        if (evidenceNode == null || evidenceNode.isNull() || evidenceNode.isMissingNode()) {
            return MAPPER.nullNode();
        }
        JsonNode activeSchema = JsonSchemaResolver.resolveLocal(schema, root);
        if (isObjectSchema(activeSchema) && evidenceNode.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            evidenceNode.fields().forEachRemaining(e -> {
                JsonNode childSchema = activeSchema.path("properties").path(e.getKey());
                out.set(e.getKey(), unwrap(e.getValue(), childSchema, root));
            });
            return out;
        }
        if (isArraySchema(activeSchema) && evidenceNode.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode item : evidenceNode) {
                out.add(unwrap(item, activeSchema.path("items"), root));
            }
            return out;
        }
        if (isEvidenceLeaf(evidenceNode)) {
            return evidenceNode.path("value").deepCopy();
        }
        return evidenceNode.deepCopy();
    }

    static Map<String, String> quoteMap(JsonNode evidenceNode) {
        var out = new LinkedHashMap<String, String>();
        collectQuotes("", evidenceNode, out);
        return Map.copyOf(out);
    }

    static Map<String, String> quoteMap(JsonNode evidenceNode, JsonNode schema) {
        var out = new LinkedHashMap<String, String>();
        collectQuotes("", evidenceNode, schema, schema, out);
        return Map.copyOf(out);
    }

    private static ObjectNode leafSchema(JsonNode valueSchema) {
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set(
                "value",
                valueSchema == null || valueSchema.isMissingNode()
                        ? MAPPER.createObjectNode()
                        : valueSchema.deepCopy());
        ObjectNode quote = MAPPER.createObjectNode();
        quote.put("type", "string");
        quote.put("minLength", 1);
        properties.set("exactQuote", quote);
        wrapper.set("properties", properties);
        ArrayNode required = MAPPER.createArrayNode();
        required.add("value");
        required.add("exactQuote");
        wrapper.set("required", required);
        wrapper.put("additionalProperties", false);
        return wrapper;
    }

    private static boolean isEvidenceLeaf(JsonNode node) {
        return node != null && node.isObject() && node.has("value") && node.has("exactQuote");
    }

    private static boolean isObjectSchema(JsonNode schema) {
        return schema != null
                && schema.path("properties").isObject()
                && ("object".equals(schema.path("type").asText()) || schema.has("properties"));
    }

    private static boolean isArraySchema(JsonNode schema) {
        return schema != null
                && schema.has("items")
                && ("array".equals(schema.path("type").asText()) || schema.has("items"));
    }

    private static void collectQuotes(String path, JsonNode node, Map<String, String> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (isEvidenceLeaf(node)) {
            String quote = node.path("exactQuote").asText();
            if (!quote.isBlank()) {
                out.put(path, quote);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> collectQuotes(joinPath(path, e.getKey()), e.getValue(), out));
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectQuotes(path + "[" + i + "]", node.get(i), out);
            }
        }
    }

    private static void collectQuotes(
            String path, JsonNode node, JsonNode schema, JsonNode root, Map<String, String> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        JsonNode activeSchema = JsonSchemaResolver.resolveLocal(schema, root);
        if (isObjectSchema(activeSchema) && node.isObject()) {
            node.fields()
                    .forEachRemaining(e -> collectQuotes(
                            joinPath(path, e.getKey()),
                            e.getValue(),
                            activeSchema.path("properties").path(e.getKey()),
                            root,
                            out));
            return;
        }
        if (isArraySchema(activeSchema) && node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectQuotes(path + "[" + i + "]", node.get(i), activeSchema.path("items"), root, out);
            }
            return;
        }
        if (isEvidenceLeaf(node)) {
            String quote = node.path("exactQuote").asText();
            if (!quote.isBlank()) {
                out.put(path, quote);
            }
        }
    }

    private static String joinPath(String parent, String child) {
        return parent.isEmpty() ? child : parent + "." + child;
    }
}
