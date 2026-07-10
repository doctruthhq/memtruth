package ai.doctruth.cli;

import java.util.List;

import ai.doctruth.JsonSchema;
import ai.doctruth.internal.schema.JsonSchemaCompatibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

record SchemaSummary(boolean compatible, int fieldCount, int requiredCount, List<String> errors) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static SchemaSummary from(JsonSchema schema) {
        JsonNode node = schema.node();
        var errors = JsonSchemaCompatibility.check(node);
        return new SchemaSummary(errors.isEmpty(), fieldCount(node), requiredCount(node), errors);
    }

    String toJson() throws CliException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("compatible", compatible);
        node.put("fieldCount", fieldCount);
        node.put("requiredCount", requiredCount);
        node.set("errors", MAPPER.valueToTree(errors));
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new CliException("failed to serialize schema summary", e);
        }
    }

    private static int fieldCount(JsonNode node) {
        JsonNode properties = node.path("properties");
        return properties.isObject() ? properties.size() : 0;
    }

    private static int requiredCount(JsonNode node) {
        JsonNode required = node.path("required");
        return required.isArray() ? required.size() : 0;
    }
}
