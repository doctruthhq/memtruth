package ai.doctruth.internal.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProviderSchemaProjectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void geminiProjectionInlinesLocalRefsAndDropsDefs() {
        JsonNode projected = ProviderSchemaProjection.forGemini(json("""
                {
                  "$defs": {
                    "Address": {
                      "type": "object",
                      "properties": { "city": { "type": "string" } },
                      "required": ["city"]
                    }
                  },
                  "type": "object",
                  "properties": { "address": { "$ref": "#/$defs/Address" } },
                  "required": ["address"]
                }
                """));

        assertThat(projected.has("$defs")).isFalse();
        assertThat(projected.at("/properties/address/type").asText()).isEqualTo("object");
        assertThat(projected.at("/properties/address/properties/city/type").asText())
                .isEqualTo("string");
        assertThat(projected.at("/properties/address/required/0").asText()).isEqualTo("city");
    }

    @Test
    void geminiProjectionConvertsNullableAnyOfAndTypeArrays() {
        JsonNode projected = ProviderSchemaProjection.forGemini(json("""
                {
                  "type": "object",
                  "properties": {
                    "nickname": { "anyOf": [{ "type": "string" }, { "type": "null" }] },
                    "middleName": {
                      "type": ["string", "null"],
                      "description": "optional middle name"
                    }
                  }
                }
                """));

        assertThat(projected.at("/properties/nickname/type").asText()).isEqualTo("string");
        assertThat(projected.at("/properties/nickname/nullable").asBoolean()).isTrue();
        assertThat(projected.at("/properties/middleName/type").asText()).isEqualTo("string");
        assertThat(projected.at("/properties/middleName/nullable").asBoolean()).isTrue();
        assertThat(projected.at("/properties/middleName/description").asText()).isEqualTo("optional middle name");
    }

    @Test
    void geminiProjectionMergesAllOfAndProjectsArrayItems() {
        JsonNode projected = ProviderSchemaProjection.forGemini(json("""
                {
                  "allOf": [
                    {
                      "type": "object",
                      "properties": { "tags": { "type": "array", "items": { "type": "string" } } }
                    },
                    { "required": ["tags"], "minItems": 1, "maxItems": 2 }
                  ]
                }
                """));

        assertThat(projected.path("type").asText()).isEqualTo("object");
        assertThat(projected.at("/properties/tags/type").asText()).isEqualTo("array");
        assertThat(projected.at("/properties/tags/items/type").asText()).isEqualTo("string");
        assertThat(projected.at("/required/0").asText()).isEqualTo("tags");
        assertThat(projected.path("minItems").asInt()).isEqualTo(1);
        assertThat(projected.path("maxItems").asInt()).isEqualTo(2);
    }

    @Test
    void geminiProjectionKeepsProviderSafeScalarKeywords() {
        JsonNode projected = ProviderSchemaProjection.forGemini(json("""
                {
                  "type": "string",
                  "format": "date",
                  "description": "published date",
                  "enum": ["2026-05-08"]
                }
                """));

        assertThat(projected.path("type").asText()).isEqualTo("string");
        assertThat(projected.path("format").asText()).isEqualTo("date");
        assertThat(projected.path("description").asText()).isEqualTo("published date");
        assertThat(projected.at("/enum/0").asText()).isEqualTo("2026-05-08");
    }

    @Test
    void geminiProjectionReturnsEmptyObjectForUnsupportedRefs() {
        JsonNode projected = ProviderSchemaProjection.forGemini(json("""
                { "$ref": "https://example.com/schema.json" }
                """));

        assertThat(projected.isObject()).isTrue();
        assertThat(projected.size()).isZero();
    }

    private static JsonNode json(String source) {
        try {
            return MAPPER.readTree(source);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
