package ai.doctruth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import ai.doctruth.internal.citation.CitationMatcher;
import ai.doctruth.internal.citation.EvidenceGate;
import ai.doctruth.internal.render.SectionRenderer;
import ai.doctruth.internal.schema.JavaNativeObjectMapper;
import ai.doctruth.internal.schema.JsonSchemaBuilder;
import ai.doctruth.internal.schema.JsonSchemaValidator;
import ai.doctruth.spi.AuditEvent;
import ai.doctruth.spi.AuditEventListener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable fluent builder for one extraction call. Each {@code with*} method returns a
 * NEW instance — the receiver is never mutated, so a single builder can be safely shared
 * across threads and re-used across {@link #run(ParsedDocument)} calls.
 *
 * @since 0.1.0
 */
public final class ExtractionBuilder<T> {

    private static final Logger LOG = LoggerFactory.getLogger(ExtractionBuilder.class);

    private static final ObjectMapper MAPPER = JavaNativeObjectMapper.create();

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final LlmProvider provider;
    private final String prompt;
    private final Class<T> type;
    private final ExtractionBuilderState<T> state;

    private ExtractionBuilder(LlmProvider provider, String prompt, Class<T> type, ExtractionBuilderState<T> state) {
        this.provider = provider;
        this.prompt = prompt;
        this.type = type;
        this.state = state;
    }

    static <T> ExtractionBuilder<T> create(LlmProvider provider, String prompt, Class<T> type) {
        return new ExtractionBuilder<>(provider, prompt, type, ExtractionBuilderState.defaults());
    }

    public ExtractionBuilder<T> withProvenance() {
        return copy(state.withProvenance());
    }

    public ExtractionBuilder<T> withBitemporal() {
        return copy(state.withBitemporal());
    }

    public ExtractionBuilder<T> withConfidence() {
        return copy(state.withConfidence());
    }

    public ExtractionBuilder<T> withMaxRetries(int n) {
        return copy(state.withMaxRetries(n));
    }

    public ExtractionBuilder<T> withContextStrategy(ContextStrategy s) {
        return copy(state.withContextStrategy(s));
    }

    public ExtractionBuilder<T> withSourcePublishedAt(Instant t) {
        return copy(state.withSourcePublishedAt(t));
    }

    public <V> ExtractionBuilder<T> withFieldConstraint(
            String fieldPath, Class<V> valueType, Predicate<V> predicate, String message) {
        return copy(state.withFieldConstraint(fieldPath, valueType, predicate, message));
    }

    public ExtractionBuilder<T> withObjectConstraint(Predicate<T> predicate, String message) {
        return copy(state.withObjectConstraint(predicate, message));
    }

    /**
     * Plug an {@link AuditEventListener} into this builder so every extraction emits one
     * SIEM-friendly event. The default is {@link AuditEventListener#NOOP}; opt-in only.
     *
     * @throws NullPointerException if {@code listener} is null.
     */
    public ExtractionBuilder<T> withAuditListener(AuditEventListener listener) {
        return copy(state.withAuditListener(listener));
    }

    private ExtractionBuilder<T> copy(ExtractionBuilderState<T> nextState) {
        return new ExtractionBuilder<>(provider, prompt, type, nextState);
    }

    /**
     * Execute the configured extraction.
     *
     * @throws NullPointerException if {@code doc} is null.
     * @throws ExtractionException  on provider or schema-parse failure (cause-chain preserved).
     */
    public ExtractionResult<T> run(ParsedDocument doc) throws ExtractionException {
        Objects.requireNonNull(doc, "doc");

        try {
            ExtractionResult<T> result = doRun(doc);
            state.auditListener.onEvent(new AuditEvent(
                    "extraction.success",
                    Instant.now(),
                    Map.of(
                            "provider", provider.name(),
                            "modelVersion", result.provenance().modelVersion(),
                            "retries", String.valueOf(result.provenance().retries()))));
            return result;
        } catch (ExtractionException e) {
            state.auditListener.onEvent(new AuditEvent(
                    "extraction.failed",
                    Instant.now(),
                    Map.of(
                            "provider", provider.name(),
                            "errorCode", e.errorCode())));
            throw e;
        }
    }

    private ExtractionResult<T> doRun(ParsedDocument doc) throws ExtractionException {
        String userPrompt =
                (state.contextStrategy != null) ? state.contextStrategy.assemble(doc) : renderUserPrompt(doc);
        var schema = JsonSchemaBuilder.forType(type);
        var request =
                new ProviderRequest(prompt, userPrompt, schema, new ProviderOptions(state.maxRetries, DEFAULT_TIMEOUT));

        for (int retry = 0; retry <= state.maxRetries; retry++) {
            ProviderResponse response = callProvider(request);
            try {
                T value = parseValue(response.rawJson(), schema, retry);
                state.constraints.validate(value, retry);
                Map<String, Citation> matched = EvidenceGate.match(
                        value, doc, state.recordProvenance || state.recordConfidence, state.recordProvenance, retry);
                return toResult(response, value, matched, retry);
            } catch (ExtractionException e) {
                if (retry == state.maxRetries) {
                    throw e;
                }
                LOG.warn(
                        "retrying extraction validation provider={} attempt={}/{}",
                        provider.name(),
                        retry + 1,
                        state.maxRetries);
            }
        }
        throw new ExtractionException(
                "EXTRACTION_RETRY_STATE_INVALID", "extraction retry loop exhausted unexpectedly", state.maxRetries);
    }

    private ExtractionResult<T> toResult(
            ProviderResponse response, T value, Map<String, Citation> matched, int retries) {
        Map<String, Citation> citations = state.recordProvenance ? matched : Map.of();
        Map<String, Confidence> confidence = state.recordConfidence ? confidenceFrom(matched) : Map.of();
        var provenance = new Provenance(
                provider.name(),
                response.usage().modelVersion(),
                Instant.now(),
                Optional.ofNullable(state.sourcePublishedAt),
                provider.region(),
                Optional.empty(),
                retries);
        return new ExtractionResult<>(value, citations, confidence, provenance);
    }

    private static Map<String, Confidence> confidenceFrom(Map<String, Citation> citations) {
        return citations.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> new Confidence(e.getValue().matchScore(), confidenceRationale(e.getValue()))));
    }

    private static String confidenceRationale(Citation citation) {
        return citation.matchScore() >= CitationMatcher.DEFAULT_MIN_SCORE
                ? "source citation matched above threshold"
                : "source citation matched below threshold";
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

    private T parseValue(String rawJson, JsonNode schema, int retries) throws ExtractionException {
        try {
            var value = MAPPER.readTree(rawJson);
            JsonSchemaValidator.validate(value, schema, retries);
            return MAPPER.treeToValue(value, type);
        } catch (JsonProcessingException e) {
            throw new ExtractionException(
                    "EXTRACTION_PARSE_FAILED",
                    "failed to parse provider response as " + type.getName() + ": " + e.getMessage(),
                    retries,
                    e);
        }
    }

    private static String renderUserPrompt(ParsedDocument doc) {
        return doc.sections().stream().map(SectionRenderer::render).collect(Collectors.joining("\n\n"));
    }
}
