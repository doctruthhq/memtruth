package ai.doctruth.internal.providers.openai.wire;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OpenAI {@code response_format.json_schema} payload.
 *
 * @hidden
 */
public record JsonSchemaSpec(String name, boolean strict, JsonNode schema) {}
