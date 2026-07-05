package ai.doctruth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import ai.doctruth.internal.citation.CitationMatcher;
import ai.doctruth.internal.render.SectionRenderer;
import ai.doctruth.internal.schema.JsonSchemaValidator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable fluent builder for JSON Schema-driven extraction. Use this when the caller
 * owns an external schema contract, including exported Pydantic JSON Schema.
 *
 * @since 0.1.0
 */
public final class JsonExtractionBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(JsonExtractionBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final LlmProvider provider;
    private final String prompt;
    private final JsonSchema schema;
    private final JsonExtractionBuilderState state;

    private JsonExtractionBuilder(
            LlmProvider provider, String prompt, JsonSchema schema, JsonExtractionBuilderState state) {
        this.provider = provider;
        this.prompt = prompt;
        this.schema = schema;
        this.state = state;
    }

    static JsonExtractionBuilder create(LlmProvider provider, String prompt, JsonSchema schema) {
        return new JsonExtractionBuilder(provider, prompt, schema, JsonExtractionBuilderState.defaults());
    }

    public JsonExtractionBuilder withProvenance() {
        return copy(state.withProvenance());
    }

    public JsonExtractionBuilder withBitemporal() {
        return copy(state.withBitemporal());
    }

    public JsonExtractionBuilder withConfidence() {
        return copy(state.withConfidence());
    }

    public JsonExtractionBuilder withMaxRetries(int n) {
        return copy(state.withMaxRetries(n));
    }

    public JsonExtractionBuilder withContextStrategy(ContextStrategy strategy) {
        return copy(state.withContextStrategy(strategy));
    }

    public JsonExtractionBuilder withSourcePublishedAt(Instant sourcePublishedAt) {
        return copy(state.withSourcePublishedAt(sourcePublishedAt));
    }

    public JsonExtractionBuilder requireCitation(String fieldPath) {
        return copy(state.requireCitation(fieldPath));
    }

    public JsonExtractionBuilder withEvidenceFirst() {
        return copy(state.withEvidenceFirst());
    }

    public ExtractionResult<JsonNode> runJson(ParsedDocument doc) throws ExtractionException {
        Objects.requireNonNull(doc, "doc");
        String repairContext = null;
        for (int retry = 0; retry <= state.maxRetries; retry++) {
            var request = requestFor(doc, repairContext);
            ProviderResponse response = callProvider(request);
            try {
                JsonNode rawValue = parseJson(response.rawJson(), retry);
                if (state.evidenceFirst) {
                    JsonSchemaValidator.validate(rawValue, EvidenceFirstJson.responseSchema(schema.node()), retry);
                }
                JsonNode value = state.evidenceFirst ? EvidenceFirstJson.unwrap(rawValue) : rawValue;
                JsonSchemaValidator.validate(value, schema.node(), retry);
                Object citationSource = state.evidenceFirst ? EvidenceFirstJson.quoteMap(rawValue) : value;
                Map<String, Citation> citations = citations(citationSource, doc, retry);
                return result(response, value, citations, retry);
            } catch (ExtractionException e) {
                if (retry == state.maxRetries) {
                    throw e;
                }
                LOG.warn(
                        "retrying JSON extraction validation provider={} attempt={}/{}",
                        provider.name(),
                        retry + 1,
                        state.maxRetries);
                repairContext = e.getMessage();
            }
        }
        throw new ExtractionException(
                "EXTRACTION_RETRY_STATE_INVALID",
                "JSON extraction retry loop exhausted unexpectedly",
                state.maxRetries);
    }

    private JsonExtractionBuilder copy(JsonExtractionBuilderState next) {
        return new JsonExtractionBuilder(provider, prompt, schema, next);
    }

    private ProviderRequest requestFor(ParsedDocument doc, String repairContext) throws ExtractionException {
        String userPrompt = state.contextStrategy == null ? renderUserPrompt(doc) : state.contextStrategy.assemble(doc);
        if (repairContext != null && !repairContext.isBlank()) {
            userPrompt = userPrompt + repairInstructions(repairContext);
        }
        JsonNode responseSchema = state.evidenceFirst ? EvidenceFirstJson.responseSchema(schema.node()) : schema.node();
        return new ProviderRequest(
                prompt, userPrompt, responseSchema, new ProviderOptions(state.maxRetries, DEFAULT_TIMEOUT));
    }

    private static String repairInstructions(String repairContext) {
        return "\n\nPrevious extraction failed validation.\n"
                + "Validation errors:\n"
                + repairContext
                + "\nReturn corrected JSON only.";
    }

    private ProviderResponse callProvider(ProviderRequest request) throws ExtractionException {
        try {
            LOG.debug("calling provider={} maxRetries={}", provider.name(), state.maxRetries);
            return provider.complete(request);
        } catch (ProviderException e) {
            throw new ExtractionException(
                    "EXTRACTION_PROVIDER_FAILED", "provider " + provider.name() + " failed: " + e.getMessage(), 0, e);
        }
    }

    private static JsonNode parseJson(String rawJson, int retries) throws ExtractionException {
        try {
            return MAPPER.readTree(rawJson);
        } catch (JsonProcessingException e) {
            throw new ExtractionException(
                    "EXTRACTION_PARSE_FAILED",
                    "failed to parse provider response as JSON: " + e.getMessage(),
                    retries,
                    e);
        }
    }

    private Map<String, Citation> citations(Object citationSource, ParsedDocument doc, int retries)
            throws ExtractionException {
        if (!state.recordProvenance && !state.recordConfidence && state.requiredCitations.isEmpty()) {
            return Map.of();
        }
        Map<String, Citation> matched = new CitationMatcher().matchAll(citationSource, doc);
        requireStrongCitations(matched, retries);
        if (state.recordProvenance || state.recordConfidence) {
            return matched;
        }
        return matched.entrySet().stream()
                .filter(e -> state.requiredCitations.contains(e.getKey()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void requireStrongCitations(Map<String, Citation> matched, int retries) throws ExtractionException {
        for (String fieldPath : state.requiredCitations) {
            Citation citation = matched.get(fieldPath);
            if (citation == null || citation.matchScore() < CitationMatcher.DEFAULT_MIN_SCORE) {
                throw new ExtractionException(
                        "EXTRACTION_EVIDENCE_MISSING",
                        "field " + fieldPath + " citation evidence is below threshold",
                        retries);
            }
        }
    }

    private ExtractionResult<JsonNode> result(
            ProviderResponse response, JsonNode value, Map<String, Citation> citations, int retries) {
        Map<String, Confidence> confidence = state.recordConfidence ? confidenceFrom(citations) : Map.of();
        var provenance = new Provenance(
                provider.name(),
                response.usage().modelVersion(),
                Instant.now(),
                Optional.ofNullable(state.sourcePublishedAt),
                provider.region(),
                Optional.empty(),
                retries);
        return new ExtractionResult<>(
                value,
                state.recordProvenance || !state.requiredCitations.isEmpty() ? citations : Map.of(),
                confidence,
                provenance);
    }

    private static Map<String, Confidence> confidenceFrom(Map<String, Citation> citations) {
        return citations.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> new Confidence(e.getValue().matchScore(), "source evidence quote matched for JSON field")));
    }

    private static String renderUserPrompt(ParsedDocument doc) {
        return doc.sections().stream().map(SectionRenderer::render).collect(Collectors.joining("\n\n"));
    }
}
