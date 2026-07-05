package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EvidenceFirstJsonExtractionContractTest {

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

        assertThat(captured.get()
                        .path("properties")
                        .path("partyA")
                        .path("properties")
                        .has("value"))
                .isTrue();
        assertThat(captured.get()
                        .path("properties")
                        .path("partyA")
                        .path("properties")
                        .has("exactQuote"))
                .isTrue();
        assertThat(result.value().path("partyA").asText()).isEqualTo("Acme");
        assertThat(result.value().path("totalValue").asInt()).isEqualTo(1000);
        assertThat(result.citations().get("partyA").exactQuote()).isEqualTo("Party A: Acme");
        assertThat(result.confidence().get("partyA").rationale()).contains("evidence quote");
    }

    @Test
    @DisplayName("evidence-first JSON is schema-aware when user fields are named value and exactQuote")
    void evidenceFirstJsonDoesNotCollapseBusinessFieldsNamedLikeEvidenceWrapper() throws ExtractionException {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "claim": {
                      "type": "object",
                      "properties": {
                        "value": { "type": "string" },
                        "exactQuote": { "type": "string" }
                      },
                      "required": ["value", "exactQuote"],
                      "additionalProperties": false
                    }
                  },
                  "required": ["claim"],
                  "additionalProperties": false
                }
                """;
        LlmProvider fake = new AnthropicProvider("test-key") {
            @Override
            public ProviderResponse complete(ProviderRequest req) {
                return response("""
                        {
                          "claim": {
                            "value": {
                              "value": "covered",
                              "exactQuote": "Claim value: covered"
                            },
                            "exactQuote": {
                              "value": "Loss stated in policy",
                              "exactQuote": "Quoted policy sentence"
                            }
                          }
                        }
                        """);
            }
        };

        var result = DocTruth.from(fake)
                .extractJson("extract claim", JsonSchema.from(schema))
                .withEvidenceFirst()
                .withProvenance()
                .runJson(doc("Claim value: covered. Quoted policy sentence."));

        assertThat(result.value().path("claim").path("value").asText()).isEqualTo("covered");
        assertThat(result.value().path("claim").path("exactQuote").asText()).isEqualTo("Loss stated in policy");
        assertThat(result.citations()).containsKeys("claim.value", "claim.exactQuote");
    }

    @Test
    @DisplayName("evidence-first JSON expands local $defs refs before wrapping leaves")
    void evidenceFirstJsonWrapsLocalRefLeaves() throws ExtractionException {
        String schema = """
                {
                  "type": "object",
                  "$defs": {
                    "party": {
                      "type": "object",
                      "properties": {
                        "name": { "type": "string" }
                      },
                      "required": ["name"],
                      "additionalProperties": false
                    }
                  },
                  "properties": {
                    "party": { "$ref": "#/$defs/party" }
                  },
                  "required": ["party"],
                  "additionalProperties": false
                }
                """;
        var captured = new AtomicReference<JsonNode>();
        LlmProvider fake = new AnthropicProvider("test-key") {
            @Override
            public ProviderResponse complete(ProviderRequest req) {
                captured.set(req.responseSchema());
                return response("""
                        {
                          "party": {
                            "name": {
                              "value": "Acme",
                              "exactQuote": "Party: Acme"
                            }
                          }
                        }
                        """);
            }
        };

        var result = DocTruth.from(fake)
                .extractJson("extract party", JsonSchema.from(schema))
                .withEvidenceFirst()
                .withProvenance()
                .runJson(doc("Party: Acme"));

        assertThat(captured.get()
                        .path("properties")
                        .path("party")
                        .path("properties")
                        .path("name")
                        .path("properties")
                        .has("value"))
                .isTrue();
        assertThat(result.value().path("party").path("name").asText()).isEqualTo("Acme");
        assertThat(result.citations()).containsKey("party.name");
    }

    private static ParsedDocument doc(String text) {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var section = new TextSection(text, loc);
        var meta = new DocumentMetadata("contract.pdf", 1, Optional.empty());
        return new ParsedDocument("doc-json-schema", List.of(section), meta);
    }

    private static ProviderResponse response(String rawJson) {
        return new ProviderResponse(rawJson, new ProviderUsage(120, 18, "claude-sonnet-4-7-test"));
    }
}
