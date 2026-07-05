package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EvidenceFirstJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void responseSchemaWrapsObjectLeavesAndArrayItems() throws Exception {
        var schema = MAPPER.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "party": { "type": "string" },
                    "items": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "amount": { "type": "number" }
                        }
                      }
                    }
                  },
                  "additionalProperties": false
                }
                """);

        var wrapped = EvidenceFirstJson.responseSchema(schema);

        assertThat(wrapped.path("properties").path("party").path("properties").has("value"))
                .isTrue();
        assertThat(wrapped.path("properties").path("party").path("properties").has("exactQuote"))
                .isTrue();
        assertThat(wrapped.path("properties")
                        .path("items")
                        .path("items")
                        .path("properties")
                        .path("amount")
                        .path("properties")
                        .path("value")
                        .path("type")
                        .asText())
                .isEqualTo("number");
    }

    @Test
    void responseSchemaWrapsNullMissingAndPrimitiveSchemasAsLeaves() throws Exception {
        var nullWrapped = EvidenceFirstJson.responseSchema(null);
        var missingWrapped = EvidenceFirstJson.responseSchema(MAPPER.missingNode());
        var primitiveWrapped = EvidenceFirstJson.responseSchema(MAPPER.readTree("{\"type\":\"boolean\"}"));

        assertThat(nullWrapped.path("properties").path("value").isObject()).isTrue();
        assertThat(missingWrapped.path("properties").path("value").isObject()).isTrue();
        assertThat(primitiveWrapped
                        .path("properties")
                        .path("value")
                        .path("type")
                        .asText())
                .isEqualTo("boolean");
    }

    @Test
    void unwrapConvertsEvidenceLeavesInsideObjectsAndArrays() throws Exception {
        var raw = MAPPER.readTree("""
                {
                  "party": { "value": "Acme", "exactQuote": "Party: Acme" },
                  "lines": [
                    { "amount": { "value": 42, "exactQuote": "Amount: 42" } },
                    null
                  ]
                }
                """);

        var unwrapped = EvidenceFirstJson.unwrap(raw);

        assertThat(unwrapped.path("party").asText()).isEqualTo("Acme");
        assertThat(unwrapped.path("lines").get(0).path("amount").asInt()).isEqualTo(42);
        assertThat(unwrapped.path("lines").get(1).isNull()).isTrue();
    }

    @Test
    void unwrapNullReturnsJsonNullAndNonEvidenceScalarIsCopied() throws Exception {
        assertThat(EvidenceFirstJson.unwrap(null).isNull()).isTrue();
        assertThat(EvidenceFirstJson.unwrap(MAPPER.readTree("\"plain\"")).asText())
                .isEqualTo("plain");
    }

    @Test
    void quoteMapCollectsNestedQuotesAndSkipsBlankQuotes() throws Exception {
        var raw = MAPPER.readTree("""
                {
                  "party": { "value": "Acme", "exactQuote": "Party: Acme" },
                  "lines": [
                    { "amount": { "value": 42, "exactQuote": "Amount: 42" } },
                    { "memo": { "value": "ignored", "exactQuote": " " } }
                  ],
                  "noQuote": { "value": "not a leaf" }
                }
                """);

        assertThat(EvidenceFirstJson.quoteMap(raw))
                .containsEntry("party", "Party: Acme")
                .containsEntry("lines[0].amount", "Amount: 42")
                .doesNotContainKey("lines[1].memo")
                .doesNotContainKey("noQuote");
        assertThat(EvidenceFirstJson.quoteMap(null)).isEmpty();
    }
}
