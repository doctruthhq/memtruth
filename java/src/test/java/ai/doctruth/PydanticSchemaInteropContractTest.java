package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pydantic v2 exports ordinary JSON Schema with $defs/$ref and nullable unions.
 * DocTruth must validate that contract locally without requiring Python at runtime.
 */
class PydanticSchemaInteropContractTest {

    private static final String PERSON_SCHEMA = """
            {
              "$defs": {
                "Address": {
                  "type": "object",
                  "properties": {
                    "city": { "type": "string", "minLength": 2 },
                    "postcode": { "type": "string", "pattern": "^[0-9]{4}$" }
                  },
                  "required": ["city", "postcode"],
                  "additionalProperties": false
                },
                "Person": {
                  "type": "object",
                  "properties": {
                    "name": { "type": "string", "minLength": 1 },
                    "age": { "type": "integer", "minimum": 18 },
                    "address": { "$ref": "#/$defs/Address" },
                    "nickname": {
                      "anyOf": [{ "type": "string" }, { "type": "null" }],
                      "default": null
                    }
                  },
                  "required": ["name", "age", "address"],
                  "additionalProperties": false
                }
              },
              "$ref": "#/$defs/Person"
            }
            """;

    @Test
    @DisplayName("accepts nested Pydantic v2 $defs/$ref schemas")
    void acceptsNestedPydanticSchema() throws ExtractionException {
        var json = """
                {"name":"Alex","age":30,"address":{"city":"Brisbane","postcode":"4000"},"nickname":null}
                """;

        var result = DocTruth.from(provider(json))
                .extractJson("extract person", JsonSchema.from(PERSON_SCHEMA))
                .runJson(doc());

        assertThat(result.value().path("address").path("city").asText()).isEqualTo("Brisbane");
        assertThat(result.value().path("nickname").isNull()).isTrue();
    }

    @Test
    @DisplayName("rejects values that violate nested $ref targets and nullable anyOf fields")
    void rejectsInvalidNestedPydanticSchemaValues() {
        var json = """
                {"name":"Alex","age":30,"address":123,"nickname":7}
                """;

        assertThatThrownBy(() -> DocTruth.from(provider(json))
                        .extractJson("extract person", JsonSchema.from(PERSON_SCHEMA))
                        .runJson(doc()))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("address")
                .hasMessageContaining("expected object")
                .hasMessageContaining("nickname")
                .hasMessageContaining("anyOf")
                .satisfies(ex -> assertThat(((ExtractionException) ex).errorCode())
                        .isEqualTo("EXTRACTION_SCHEMA_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("supports JSON Schema type arrays for nullable Pydantic fields")
    void supportsNullableTypeArrays() throws ExtractionException {
        var schema = """
                {
                  "type": "object",
                  "properties": { "middleName": { "type": ["string", "null"] } },
                  "required": ["middleName"],
                  "additionalProperties": false
                }
                """;

        var result = DocTruth.from(provider("{\"middleName\":null}"))
                .extractJson("extract nullable", JsonSchema.from(schema))
                .runJson(doc());

        assertThat(result.value().path("middleName").isNull()).isTrue();
        assertThatThrownBy(() -> DocTruth.from(provider("{\"middleName\":7}"))
                        .extractJson("extract nullable", JsonSchema.from(schema))
                        .runJson(doc()))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("middleName")
                .hasMessageContaining("expected string or null");
    }

    @Test
    @DisplayName("enforces common scalar and array constraints from exported schemas")
    void enforcesScalarAndArrayConstraints() {
        var schema = """
                {
                  "type": "object",
                  "properties": {
                    "name": { "type": "string", "minLength": 2, "maxLength": 20 },
                    "score": { "type": "number", "minimum": 0, "maximum": 100 },
                    "tags": {
                      "type": "array",
                      "items": { "type": "string" },
                      "minItems": 1,
                      "maxItems": 2
                    }
                  },
                  "required": ["name", "score", "tags"],
                  "additionalProperties": false
                }
                """;
        var invalid = """
                {"name":"J","score":101,"tags":[]}
                """;

        assertThatThrownBy(() -> DocTruth.from(provider(invalid))
                        .extractJson("extract constrained", JsonSchema.from(schema))
                        .runJson(doc()))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("name")
                .hasMessageContaining("minLength")
                .hasMessageContaining("score")
                .hasMessageContaining("maximum")
                .hasMessageContaining("tags")
                .hasMessageContaining("minItems");
    }

    @Test
    @DisplayName("enforces allOf and oneOf composition")
    void enforcesCompositionKeywords() {
        var schema = """
                {
                  "type": "object",
                  "properties": {
                    "code": { "allOf": [{ "type": "string" }, { "minLength": 3 }] },
                    "identifier": { "oneOf": [{ "type": "string" }, { "type": "integer" }] }
                  },
                  "required": ["code", "identifier"]
                }
                """;

        assertThatThrownBy(() -> DocTruth.from(provider("{\"code\":\"AB\",\"identifier\":true}"))
                        .extractJson("extract composition", JsonSchema.from(schema))
                        .runJson(doc()))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("code")
                .hasMessageContaining("minLength")
                .hasMessageContaining("identifier")
                .hasMessageContaining("oneOf");
    }

    private static ParsedDocument doc() {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var section = new TextSection("Alex lives in Brisbane 4000", loc);
        var meta = new DocumentMetadata("person.pdf", 1, Optional.empty());
        return new ParsedDocument("pydantic-doc", List.of(section), meta);
    }

    private static LlmProvider provider(String rawJson) {
        return new AnthropicProvider("test-key") {
            @Override
            public ProviderResponse complete(ProviderRequest req) {
                return new ProviderResponse(rawJson, new ProviderUsage(40, 10, "test-model"));
            }
        };
    }
}
