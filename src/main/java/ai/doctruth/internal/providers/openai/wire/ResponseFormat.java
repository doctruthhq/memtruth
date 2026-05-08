package ai.doctruth.internal.providers.openai.wire;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code response_format} hint sent on every Chat Completions request. We always
 * pass a native JSON Schema contract so providers that support structured output can
 * enforce the same schema DocTruth validates locally.
 *
 * @hidden
 */
public record ResponseFormat(String type, JsonSchemaSpec json_schema) {

    public static ResponseFormat jsonSchema(JsonNode schema) {
        return new ResponseFormat("json_schema", new JsonSchemaSpec("doctruth_extract", true, schema.deepCopy()));
    }
}
