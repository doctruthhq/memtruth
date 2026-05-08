package ai.doctruth.internal.providers.gemini;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

import ai.doctruth.ProviderException;
import ai.doctruth.ProviderRequest;
import ai.doctruth.ProviderResponse;
import ai.doctruth.ProviderUsage;
import ai.doctruth.internal.http.JsonHttpClient;
import ai.doctruth.internal.providers.gemini.wire.Candidate;
import ai.doctruth.internal.providers.gemini.wire.Content;
import ai.doctruth.internal.providers.gemini.wire.GenerateContentRequest;
import ai.doctruth.internal.providers.gemini.wire.GenerateContentResponse;
import ai.doctruth.internal.providers.gemini.wire.GenerationConfig;
import ai.doctruth.internal.providers.gemini.wire.Part;
import ai.doctruth.internal.providers.gemini.wire.SystemInstruction;
import ai.doctruth.internal.providers.gemini.wire.UsageMetadata;
import ai.doctruth.internal.retry.RetryGate;
import ai.doctruth.internal.schema.ProviderSchemaProjection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Hand-rolled HTTP delegate for the Gemini {@code generateContent} endpoint. Translates a
 * {@link ProviderRequest} into a {@link GenerateContentRequest}, POSTs it via
 * {@link JsonHttpClient}, and reads {@code candidates[0].content.parts[0].text} as the raw
 * JSON ProviderResponse payload. Retries are gated by {@link RetryGate} (per ADR 0004).
 *
 * <p>NOT public API — see {@code package-info.java}.
 *
 * @hidden
 */
public final class GeminiHttpClient {

    /** Logical provider name surfaced in {@link ProviderException#providerName()}. */
    private static final String PROVIDER = "gemini";

    /** Response MIME type asked from Gemini so the model emits raw JSON, not Markdown. */
    private static final String JSON_MIME = "application/json";

    private final String apiKey;
    private final URI endpoint;
    private final String model;
    private final JsonHttpClient http;
    private final RetryGate retry;

    public GeminiHttpClient(String apiKey, URI endpoint, String model) {
        this(apiKey, endpoint, model, new JsonHttpClient(new ObjectMapper()), defaultRetry());
    }

    GeminiHttpClient(String apiKey, URI endpoint, String model, JsonHttpClient http, RetryGate retry) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.model = Objects.requireNonNull(model, "model");
        this.http = Objects.requireNonNull(http, "http");
        this.retry = Objects.requireNonNull(retry, "retry");
    }

    private static RetryGate defaultRetry() {
        return new RetryGate(2, Duration.ofMillis(50), Duration.ofSeconds(2));
    }

    public ProviderResponse complete(ProviderRequest request) throws ProviderException {
        Objects.requireNonNull(request, "request");
        GenerateContentRequest body = buildBody(request);
        URI uri = URI.create(endpoint + "/v1beta/models/" + model + ":generateContent");
        Duration timeout = request.options().timeout();

        GenerateContentResponse response = retry.run(
                () -> http.post(uri, body, GenerateContentResponse.class, headers(), PROVIDER, timeout), PROVIDER);
        return toProviderResponse(response);
    }

    private Map<String, String> headers() {
        return Map.of("x-goog-api-key", apiKey);
    }

    private static GenerateContentRequest buildBody(ProviderRequest request) {
        SystemInstruction system = new SystemInstruction(List.of(new Part(request.systemPrompt())));
        Content userTurn = new Content("user", List.of(new Part(request.userPrompt())));
        JsonNode responseSchema = ProviderSchemaProjection.forGemini(request.responseSchema());
        return new GenerateContentRequest(system, List.of(userTurn), new GenerationConfig(JSON_MIME, responseSchema));
    }

    private static ProviderResponse toProviderResponse(GenerateContentResponse response) throws ProviderException {
        String rawJson = extractText(response);
        UsageMetadata usage = response.usageMetadata();
        int input = usage == null ? 0 : usage.promptTokenCount();
        int output = usage == null ? 0 : usage.candidatesTokenCount();
        String modelVersion = response.modelVersion();
        if (modelVersion == null || modelVersion.isBlank()) {
            throw invalid("response missing modelVersion");
        }
        return new ProviderResponse(rawJson, new ProviderUsage(input, output, modelVersion));
    }

    private static String extractText(GenerateContentResponse response) throws ProviderException {
        List<Candidate> candidates = response.candidates();
        if (candidates == null || candidates.isEmpty()) {
            throw invalid("response candidates array is empty");
        }
        Candidate first = candidates.get(0);
        Content content = first == null ? null : first.content();
        List<Part> parts = content == null ? null : content.parts();
        if (parts == null || parts.isEmpty()) {
            throw invalid("response candidate has no content parts");
        }
        Part firstPart = parts.get(0);
        String text = firstPart == null ? null : firstPart.text();
        if (text == null || text.isBlank()) {
            throw invalid("response candidate part text is blank");
        }
        return text;
    }

    private static ProviderException invalid(String detail) {
        return new ProviderException(
                "PROVIDER_RESPONSE_INVALID",
                "invalid response from provider=" + PROVIDER + ": " + detail,
                PROVIDER,
                OptionalInt.empty(),
                false);
    }
}
