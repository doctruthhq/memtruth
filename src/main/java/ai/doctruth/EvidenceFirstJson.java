package ai.doctruth;

import java.util.LinkedHashMap;
import java.util.Map;

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
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return leafSchema(schema);
        }
        if ("object".equals(schema.path("type").asText()) && schema.path("properties").isObject()) {
            ObjectNode copy = schema.deepCopy();
            ObjectNode properties = MAPPER.createObjectNode();
            schema.path("properties").fields().forEachRemaining(e -> properties.set(e.getKey(), responseSchema(e.getValue())));
            copy.set("properties", properties);
            return copy;
        }
        if ("array".equals(schema.path("type").asText()) && schema.has("items")) {
            ObjectNode copy = schema.deepCopy();
            copy.set("items", responseSchema(schema.path("items")));
            return copy;
        }
        return leafSchema(schema);
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

    static Map<String, String> quoteMap(JsonNode evidenceNode) {
        var out = new LinkedHashMap<String, String>();
        collectQuotes("", evidenceNode, out);
        return Map.copyOf(out);
    }

    private static ObjectNode leafSchema(JsonNode valueSchema) {
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("value", valueSchema == null || valueSchema.isMissingNode() ? MAPPER.createObjectNode() : valueSchema.deepCopy());
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

    private static String joinPath(String parent, String child) {
        return parent.isEmpty() ? child : parent + "." + child;
    }
}
