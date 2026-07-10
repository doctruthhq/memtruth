package ai.doctruth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Caller-supplied JSON Schema for schema-bound extraction. This is the interchange
 * surface for exported Pydantic schemas: DocTruth consumes ordinary schema JSON and
 * does not require Python at runtime.
 *
 * @since 0.1.0
 */
public final class JsonSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode node;

    private JsonSchema(JsonNode node) {
        this.node = node.deepCopy();
    }

    public static JsonSchema from(String json) {
        Objects.requireNonNull(json, "json");
        if (json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        try {
            return new JsonSchema(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("json must be valid JSON Schema: " + e.getMessage(), e);
        }
    }

    public static JsonSchema from(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            return from(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read JSON Schema from " + path, e);
        }
    }

    public JsonNode node() {
        return node.deepCopy();
    }
}
