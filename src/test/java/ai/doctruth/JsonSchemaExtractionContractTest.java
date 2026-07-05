package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for JSON Schema-driven extraction. This is the Pydantic
 * interoperability path: callers export schema JSON and DocTruth owns local
 * validation, retry, typed JSON output, and citation gating without Python at runtime.
 */
class JsonSchemaExtractionContractTest {

    private static final String CONTRACT_SCHEMA = """
            {
              "title": "Contract",
              "type": "object",
              "properties": {
                "partyA": { "type": "string" },
                "status": { "type": "string", "enum": ["draft", "signed"] },
                "totalValue": { "type": "number" }
              },
              "required": ["partyA", "status"],
              "additionalProperties": false
            }
            """;

    private static final String VALID_JSON = """
            {"partyA":"Acme","status":"signed","totalValue":1000}
            """;

    @TempDir
    Path tempDir;

    private static ParsedDocument doc(String text) {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var section = new TextSection(text, loc);
        var meta = new DocumentMetadata("contract.pdf", 1, Optional.empty());
        return new ParsedDocument("doc-json-schema", List.of(section), meta);
    }

    private static ProviderResponse response(String rawJson) {
        return new ProviderResponse(rawJson, new ProviderUsage(120, 18, "claude-sonnet-4-7-test"));
    }

    @Nested
    @DisplayName("schema input")
    class SchemaInput {

        @Test
        @DisplayName("JsonSchema.from(String) is sent to the provider unchanged and returns JsonNode output")
        void jsonSchemaStringIsUsedAsProviderSchema() throws ExtractionException {
            var captured = new AtomicReference<JsonNode>();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    captured.set(req.responseSchema());
                    return response(VALID_JSON);
                }
            };

            var result = DocTruth.from(fake)
                    .extractJson("extract contract", JsonSchema.from(CONTRACT_SCHEMA))
                    .runJson(doc("Acme signed contract value 1000"));

            assertThat(result.value().path("partyA").asText()).isEqualTo("Acme");
            assertThat(captured.get().path("title").asText()).isEqualTo("Contract");
            assertThat(captured.get().path("properties").path("status").path("enum"))
                    .extracting(JsonNode::asText)
                    .containsExactly("draft", "signed");
        }

        @Test
        @DisplayName("JsonSchema.from(Path) loads exported Pydantic JSON Schema without a Python runtime")
        void pydanticJsonSchemaCanBeLoadedFromPath() throws Exception {
            Path schemaPath = tempDir.resolve("contract.schema.json");
            Files.writeString(schemaPath, CONTRACT_SCHEMA);
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return response(VALID_JSON);
                }
            };

            var result = DocTruth.from(fake)
                    .extractJson("extract contract", JsonSchema.from(schemaPath))
                    .runJson(doc("Acme signed contract value 1000"));

            assertThat(result.value().path("status").asText()).isEqualTo("signed");
        }

        @Test
        @DisplayName("JsonSchema accessors are defensive copies")
        void jsonSchemaNodeIsDefensivelyCopied() {
            JsonSchema schema = JsonSchema.from(CONTRACT_SCHEMA);
            JsonNode copy = schema.node();

            ((com.fasterxml.jackson.databind.node.ObjectNode) copy).put("title", "mutated");

            assertThat(schema.node().path("title").asText()).isEqualTo("Contract");
        }

        @Test
        @DisplayName("JsonSchema.from rejects null, blank, invalid, and unreadable input")
        void jsonSchemaInputInvariants() {
            assertThatThrownBy(() -> JsonSchema.from((String) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("json");
            assertThatThrownBy(() -> JsonSchema.from("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("json");
            assertThatThrownBy(() -> JsonSchema.from("{not-json"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("valid JSON Schema");
            assertThatThrownBy(() -> JsonSchema.from(tempDir.resolve("missing.schema.json")))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("failed to read JSON Schema");
        }
    }

    @Nested
    @DisplayName("local validation")
    class LocalValidation {

        @Test
        @DisplayName("missing required fields are retried and provenance records the repair attempt")
        void missingRequiredFieldIsRetried() throws ExtractionException {
            var calls = new AtomicInteger();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    int attempt = calls.incrementAndGet();
                    if (attempt == 1) {
                        return response("{\"status\":\"signed\"}");
                    }
                    return response(VALID_JSON);
                }
            };

            var result = DocTruth.from(fake)
                    .extractJson("extract contract", JsonSchema.from(CONTRACT_SCHEMA))
                    .withMaxRetries(1)
                    .runJson(doc("Acme signed contract value 1000"));

            assertThat(calls.get()).isEqualTo(2);
            assertThat(result.provenance().retries()).isEqualTo(1);
            assertThat(result.value().path("partyA").asText()).isEqualTo("Acme");
        }

        @Test
        @DisplayName("type and enum failures surface a stable schema-validation error")
        void typeAndEnumFailuresAreStable() {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return response("{\"partyA\":7,\"status\":\"closed\"}");
                }
            };

            assertThatThrownBy(() -> DocTruth.from(fake)
                            .extractJson("extract contract", JsonSchema.from(CONTRACT_SCHEMA))
                            .runJson(doc("Acme signed contract value 1000")))
                    .isInstanceOf(ExtractionException.class)
                    .hasMessageContaining("partyA")
                    .hasMessageContaining("expected string")
                    .satisfies(ex -> assertThat(((ExtractionException) ex).errorCode())
                            .isEqualTo("EXTRACTION_SCHEMA_VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("enum failures are reported after type validation succeeds")
        void enumFailuresAreStable() {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return response("{\"partyA\":\"Acme\",\"status\":\"closed\"}");
                }
            };

            assertThatThrownBy(() -> DocTruth.from(fake)
                            .extractJson("extract contract", JsonSchema.from(CONTRACT_SCHEMA))
                            .runJson(doc("Acme closed contract")))
                    .isInstanceOf(ExtractionException.class)
                    .hasMessageContaining("status")
                    .hasMessageContaining("must be one of")
                    .satisfies(ex -> assertThat(((ExtractionException) ex).errorCode())
                            .isEqualTo("EXTRACTION_SCHEMA_VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("additionalProperties=false rejects unknown JSON fields")
        void additionalPropertiesAreRejected() {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return response("{\"partyA\":\"Acme\",\"status\":\"signed\",\"extra\":\"no\"}");
                }
            };

            assertThatThrownBy(() -> DocTruth.from(fake)
                            .extractJson("extract contract", JsonSchema.from(CONTRACT_SCHEMA))
                            .runJson(doc("Acme signed contract")))
                    .isInstanceOf(ExtractionException.class)
                    .hasMessageContaining("extra")
                    .hasMessageContaining("additional property is not allowed");
        }

        @Test
        @DisplayName("arrays, integers, and booleans are locally validated")
        void arraysIntegersAndBooleansAreValidated() throws ExtractionException {
            String schema = """
                    {
                      "type": "object",
                      "properties": {
                        "scores": { "type": "array", "items": { "type": "integer" } },
                        "active": { "type": "boolean" }
                      },
                      "required": ["scores", "active"],
                      "additionalProperties": false
                    }
                    """;
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return response("{\"scores\":[1,2,3],\"active\":true}");
                }
            };

            var result = DocTruth.from(fake)
                    .extractJson("extract scores", JsonSchema.from(schema))
                    .runJson(doc("scores 1 2 3 active true"));

            assertThat(result.value().path("scores")).hasSize(3);
            assertThat(result.value().path("active").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("retry requests include validation errors so the provider can repair JSON")
        void retryPromptCarriesValidationErrors() throws ExtractionException {
            var prompts = new ArrayList<String>();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    prompts.add(req.userPrompt());
                    if (prompts.size() == 1) {
                        return response("{\"status\":\"signed\"}");
                    }
                    return response(VALID_JSON);
                }
            };

            DocTruth.from(fake)
                    .extractJson("extract contract", JsonSchema.from(CONTRACT_SCHEMA))
                    .withMaxRetries(1)
                    .runJson(doc("Acme signed contract value 1000"));

            assertThat(prompts).hasSize(2);
            assertThat(prompts.get(1))
                    .contains("Previous extraction failed validation")
                    .contains("partyA required field missing")
                    .contains("Return corrected JSON only");
        }
    }

    @Nested
    @DisplayName("citation requirements")
    class CitationRequirements {

        @Test
        @DisplayName("requireCitation(field) retries when that JSON field cannot cite the source")
        void requiredJsonFieldCitationIsRetried() throws ExtractionException {
            var calls = new AtomicInteger();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    int attempt = calls.incrementAndGet();
                    if (attempt == 1) {
                        return response("{\"partyA\":\"Missing Co\",\"status\":\"signed\",\"totalValue\":1000}");
                    }
                    return response(VALID_JSON);
                }
            };

            var result = DocTruth.from(fake)
                    .extractJson("extract contract", JsonSchema.from(CONTRACT_SCHEMA))
                    .requireCitation("partyA")
                    .withMaxRetries(1)
                    .runJson(doc("Acme signed contract value 1000"));

            assertThat(calls.get()).isEqualTo(2);
            assertThat(result.citations()).containsKey("partyA");
            assertThat(result.citations()).doesNotContainKey("status");
            assertThat(result.citations().get("partyA").matchScore()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("withProvenance and withConfidence return full JSON-field citations and confidence")
        void provenanceAndConfidenceReturnAllJsonFieldEvidence() throws ExtractionException {
            Instant publishedAt = Instant.parse("2026-01-01T00:00:00Z");
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return response(VALID_JSON);
                }
            };

            var result = DocTruth.from(fake)
                    .extractJson("extract contract", JsonSchema.from(CONTRACT_SCHEMA))
                    .withProvenance()
                    .withBitemporal()
                    .withConfidence()
                    .withSourcePublishedAt(publishedAt)
                    .runJson(doc("Acme signed contract value 1000"));

            assertThat(result.citations()).containsKeys("partyA", "status", "totalValue");
            assertThat(result.confidence()).containsKeys("partyA", "status", "totalValue");
            assertThat(result.provenance().sourcePublishedAt()).contains(publishedAt);
        }

        @Test
        @DisplayName("evidence-first JSON wraps provider schema, unwraps values, and cites exact quotes")
        void evidenceFirstJsonUsesProviderQuotesAsCitations() throws ExtractionException {
            var captured = new AtomicReference<JsonNode>();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    captured.set(req.responseSchema());
                    return response("""
                            {
                              "partyA": {
                                "value": "Acme",
                                "exactQuote": "Party A: Acme"
                              },
                              "status": {
                                "value": "signed",
                                "exactQuote": "status signed"
                              },
                              "totalValue": {
                                "value": 1000,
                                "exactQuote": "value 1000"
                              }
                            }
                            """);
                }
            };

            var result = DocTruth.from(fake)
                    .extractJson("extract contract", JsonSchema.from(CONTRACT_SCHEMA))
                    .withEvidenceFirst()
                    .withProvenance()
                    .withConfidence()
                    .runJson(doc("Party A: Acme status signed value 1000"));

            assertThat(captured.get().path("properties").path("partyA").path("properties").has("value"))
                    .isTrue();
            assertThat(captured.get().path("properties").path("partyA").path("properties").has("exactQuote"))
                    .isTrue();
            assertThat(result.value().path("partyA").asText()).isEqualTo("Acme");
            assertThat(result.value().path("totalValue").asInt()).isEqualTo(1000);
            assertThat(result.citations().get("partyA").exactQuote()).isEqualTo("Party A: Acme");
            assertThat(result.confidence().get("partyA").rationale()).contains("evidence quote");
        }

        @Test
        @DisplayName("builder invariants reject invalid retry and citation arguments")
        void builderInvariants() {
            var builder = DocTruth.from(new AnthropicProvider("test-key"))
                    .extractJson("extract contract", JsonSchema.from(CONTRACT_SCHEMA));

            assertThatThrownBy(() -> builder.withMaxRetries(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxRetries");
            assertThatThrownBy(() -> builder.requireCitation(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("fieldPath");
            assertThatThrownBy(() -> builder.requireCitation("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fieldPath");
            assertThatThrownBy(() -> builder.withSourcePublishedAt(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sourcePublishedAt");
            assertThatThrownBy(() -> builder.withContextStrategy(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("contextStrategy");
        }
    }
}
