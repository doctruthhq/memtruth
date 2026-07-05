package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonSchemaCitationContractTest {

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
