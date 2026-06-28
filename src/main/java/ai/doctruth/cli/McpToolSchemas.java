package ai.doctruth.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class McpToolSchemas {

    static final String PARSE_DOCUMENT = "doctruth.parse_document";
    static final String GET_LAYOUT_REGIONS = "doctruth.get_layout_regions";
    static final String GET_TABLE_CELLS = "doctruth.get_table_cells";
    static final String GET_EVIDENCE_SPAN = "doctruth.get_evidence_span";
    static final String VERIFY_CITATION = "doctruth.verify_citation";
    static final String WARM_MODEL_CACHE = "doctruth.warm_model_cache";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpToolSchemas() {
        throw new AssertionError("no instances");
    }

    static ObjectNode toolsListResult() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = MAPPER.createArrayNode();
        tools.add(
                tool(PARSE_DOCUMENT, "Parse a local document into evidence-backed DocTruth output.", parseDocument()));
        tools.add(tool(GET_LAYOUT_REGIONS, "Return citeable layout regions with bbox anchors.", path()));
        tools.add(tool(GET_TABLE_CELLS, "Return structured table cells with bbox anchors.", path()));
        tools.add(tool(GET_EVIDENCE_SPAN, "Return one evidence span by id.", evidenceSpan()));
        tools.add(tool(VERIFY_CITATION, "Verify a quote against a document evidence span.", verifyCitation()));
        tools.add(tool(WARM_MODEL_CACHE, "Verify local parser model cache artifacts before use.", warmModelCache()));
        result.set("tools", tools);
        return result;
    }

    private static ObjectNode tool(String name, String description, ObjectNode schema) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", schema);
        return tool;
    }

    private static ObjectNode parseDocument() {
        ObjectNode schema = path();
        ObjectNode properties = (ObjectNode) schema.path("properties");
        ObjectNode format = MAPPER.createObjectNode();
        format.put("type", "string");
        format.set(
                "enum",
                MAPPER.createArrayNode().add("compact_llm").add("json_evidence").add("json_full"));
        properties.set("format", format);
        ObjectNode sourceMap = MAPPER.createObjectNode();
        sourceMap.put("type", "boolean");
        properties.set("sourceMap", sourceMap);
        return schema;
    }

    private static ObjectNode path() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("path", stringProperty("Local document path."));
        properties.set("preset", stringProperty("Parser preset id."));
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("path"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode evidenceSpan() {
        ObjectNode schema = path();
        ((ObjectNode) schema.path("properties")).set("evidenceSpanId", stringProperty("Evidence span id."));
        ((ArrayNode) schema.path("required")).add("evidenceSpanId");
        return schema;
    }

    private static ObjectNode verifyCitation() {
        ObjectNode schema = evidenceSpan();
        ((ObjectNode) schema.path("properties")).set("quote", stringProperty("Quote to verify."));
        ((ArrayNode) schema.path("required")).add("quote");
        return schema;
    }

    private static ObjectNode warmModelCache() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("cacheDir", stringProperty("Local model cache directory."));
        ObjectNode models = MAPPER.createObjectNode();
        models.put("type", "array");
        models.set("items", modelDescriptor());
        properties.set("models", models);
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("cacheDir").add("models"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode modelDescriptor() {
        ObjectNode descriptor = MAPPER.createObjectNode();
        descriptor.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("name", stringProperty("Model name."));
        properties.set("version", stringProperty("Model version."));
        properties.set("sha256", stringProperty("Expected sha256:<hex> digest."));
        properties.set("sizeBytes", numberProperty("Expected size in bytes."));
        properties.set("required", booleanProperty("Whether this model is required."));
        descriptor.set("properties", properties);
        descriptor.set(
                "required", MAPPER.createArrayNode().add("name").add("version").add("sha256"));
        return descriptor;
    }

    private static ObjectNode stringProperty(String description) {
        ObjectNode property = MAPPER.createObjectNode();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private static ObjectNode numberProperty(String description) {
        ObjectNode property = MAPPER.createObjectNode();
        property.put("type", "integer");
        property.put("description", description);
        return property;
    }

    private static ObjectNode booleanProperty(String description) {
        ObjectNode property = MAPPER.createObjectNode();
        property.put("type", "boolean");
        property.put("description", description);
        return property;
    }
}
