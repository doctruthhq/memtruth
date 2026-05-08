package ai.doctruth.internal.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonSchemaCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptsLocalRefsInNestedSchemaShapes() {
        var errors = JsonSchemaCompatibility.check(json("""
                {
                  "$defs": {
                    "Address": { "type": "object", "properties": { "city": { "type": "string" } } }
                  },
                  "type": "object",
                  "properties": {
                    "address": { "$ref": "#/$defs/Address" },
                    "previous": {
                      "type": "array",
                      "items": { "$ref": "#/$defs/Address" }
                    },
                    "nickname": { "anyOf": [{ "type": "string" }, { "type": "null" }] }
                  }
                }
                """));

        assertThat(errors).isEmpty();
    }

    @Test
    void reportsRemoteRefsUnderPropertiesAndComposition() {
        var errors = JsonSchemaCompatibility.check(json("""
                {
                  "type": "object",
                  "properties": {
                    "address": { "$ref": "https://example.com/address.json" },
                    "role": {
                      "oneOf": [
                        { "$ref": "https://example.com/role.json" },
                        { "type": "string" }
                      ]
                    }
                  }
                }
                """));

        assertThat(errors)
                .anyMatch(error -> error.contains("$.properties.address unsupported $ref"))
                .anyMatch(error -> error.contains("$.properties.role.oneOf[0] unsupported $ref"));
    }

    @Test
    void reportsUnresolvedRefsUnderArrayItemsAndAllOf() {
        var errors = JsonSchemaCompatibility.check(json("""
                {
                  "type": "object",
                  "properties": {
                    "addresses": { "type": "array", "items": { "$ref": "#/$defs/MissingAddress" } },
                    "name": { "allOf": [{ "$ref": "#/$defs/MissingName" }] }
                  }
                }
                """));

        assertThat(errors)
                .anyMatch(error -> error.contains("$.properties.addresses.items unresolved $ref"))
                .anyMatch(error -> error.contains("$.properties.name.allOf[0] unresolved $ref"));
    }

    @Test
    void ignoresNonObjectNodes() {
        var errors = JsonSchemaCompatibility.check(json("""
                ["not", "a", "schema"]
                """));

        assertThat(errors).isEmpty();
    }

    private static JsonNode json(String source) {
        try {
            return MAPPER.readTree(source);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
