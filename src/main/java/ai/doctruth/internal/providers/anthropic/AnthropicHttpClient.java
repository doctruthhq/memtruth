package ai.doctruth.internal.providers.anthropic;

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
import ai.doctruth.internal.providers.anthropic.wire.CacheControl;
import ai.doctruth.internal.providers.anthropic.wire.ContentBlock;
import ai.doctruth.internal.providers.anthropic.wire.Message;
import ai.doctruth.internal.providers.anthropic.wire.MessagesRequest;
import ai.doctruth.internal.providers.anthropic.wire.MessagesResponse;
import ai.doctruth.internal.providers.anthropic.wire.SystemBlock;
import ai.doctruth.internal.providers.anthropic.wire.Tool;
import ai.doctruth.internal.providers.anthropic.wire.ToolChoice;
import ai.doctruth.internal.providers.anthropic.wire.Usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin HTTP client for the Anthropic Messages API. Maps {@link ProviderRequest} ↔ Anthropic
 * wire records and delegates transport to {@link JsonHttpClient} + retry to a per-call
 * {@link ai.doctruth.internal.retry.RetryGate}.
 *
 * <p>Phase 2 shape: forces structured output via the {@code "extract"} tool so the response
 * body always contains a parsed JSON object (no free-text-to-JSON parse step). Also opts the
 * system prompt into Anthropic's 5-minute ephemeral prompt cache via {@link CacheControl}.
 *
 * <p>NOT public API — see {@code package-info.java}. Anthropic-specific knowledge MUST NOT
 * cross this package boundary, per ADR 0003.
 *
 * @hidden
 */
public final class AnthropicHttpClient {

    static final String ANTHROPIC_VERSION = "2023-06-01";
    static final int MAX_TOKENS_DEFAULT = 4096;
    static final String PROVIDER_NAME = "anthropic";
    static final String EXTRACT_TOOL_NAME = "extract";
    static final String EXTRACT_TOOL_DESCRIPTION = "Extract structured data matching the requested schema.";
    static final String CACHE_CONTROL_EPHEMERAL = "ephemeral";
    static final String SYSTEM_BLOCK_TYPE_TEXT = "text";
    static final String CONTENT_TYPE_TOOL_USE = "tool_use";
    static final String TOOL_CHOICE_TYPE_TOOL = "tool";

    private static final Logger LOG = LoggerFactory.getLogger(AnthropicHttpClient.class);
    private static final Duration RETRY_INITIAL_DELAY = Duration.ofMillis(50);
    private static final Duration RETRY_MAX_DELAY = Duration.ofSeconds(2);

    private final String apiKey;
    private final URI endpoint;
    private final String model;
    private final JsonHttpClient http;
    private final ObjectMapper mapper;

    public AnthropicHttpClient(String apiKey, URI endpoint, String model) {
        this(apiKey, endpoint, model, new ObjectMapper());
    }

    AnthropicHttpClient(String apiKey, URI endpoint, String model, ObjectMapper mapper) {
        this(apiKey, endpoint, model, mapper, new JsonHttpClient(mapper));
    }

    AnthropicHttpClient(String apiKey, URI endpoint, String model, ObjectMapper mapper, JsonHttpClient http) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.model = Objects.requireNonNull(model, "model");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * Execute a single Messages-API call under the request's retry budget.
     *
     * @throws ProviderException on any HTTP, transport, retry-exhaustion, or response-shape
     *     failure; the retryable flag and httpStatus follow the underlying
     *     {@link JsonHttpClient} mapping.
     */
    public ProviderResponse complete(ProviderRequest request) throws ProviderException {
        Objects.requireNonNull(request, "request");
        MessagesRequest body = buildRequestBody(request);
        Map<String, String> headers = buildHeaders();
        var retry = new ai.doctruth.internal.retry.RetryGate(
                request.options().maxRetries(), RETRY_INITIAL_DELAY, RETRY_MAX_DELAY);
        MessagesResponse response = retry.run(
                () -> http.post(
                        endpoint,
                        body,
                        MessagesResponse.class,
                        headers,
                        PROVIDER_NAME,
                        request.options().timeout()),
                PROVIDER_NAME);
        logCacheUsage(response.usage());
        return toProviderResponse(response);
    }

    private MessagesRequest buildRequestBody(ProviderRequest request) {
        var systemBlock = new SystemBlock(
                SYSTEM_BLOCK_TYPE_TEXT, request.systemPrompt(), new CacheControl(CACHE_CONTROL_EPHEMERAL));
        var extractTool = new Tool(EXTRACT_TOOL_NAME, EXTRACT_TOOL_DESCRIPTION, request.responseSchema());
        return new MessagesRequest(
                model,
                MAX_TOKENS_DEFAULT,
                List.of(systemBlock),
                List.of(new Message("user", request.userPrompt())),
                List.of(extractTool),
                new ToolChoice(TOOL_CHOICE_TYPE_TOOL, EXTRACT_TOOL_NAME));
    }

    private Map<String, String> buildHeaders() {
        return Map.of("x-api-key", apiKey, "anthropic-version", ANTHROPIC_VERSION);
    }

    private static void logCacheUsage(Usage usage) {
        if (usage == null) {
            return;
        }
        LOG.debug(
                "anthropic call: cache_create={} cache_read={} input={} output={}",
                usage.cache_creation_input_tokens(),
                usage.cache_read_input_tokens(),
                usage.input_tokens(),
                usage.output_tokens());
    }

    private ProviderResponse toProviderResponse(MessagesResponse response) throws ProviderException {
        String rawJson = extractToolUseInput(response);
        var usage = response.usage();
        int inputTokens = usage == null ? 0 : usage.input_tokens();
        int outputTokens = usage == null ? 0 : usage.output_tokens();
        String modelVersion = response.model();
        if (modelVersion == null || modelVersion.isBlank()) {
            throw responseInvalid("missing or blank response.model");
        }
        return new ProviderResponse(rawJson, new ProviderUsage(inputTokens, outputTokens, modelVersion));
    }

    private String extractToolUseInput(MessagesResponse response) throws ProviderException {
        List<ContentBlock> content = response.content();
        if (content == null || content.isEmpty()) {
            throw responseInvalid("response.content is empty");
        }
        ContentBlock first = content.get(0);
        if (first == null) {
            throw responseInvalid("response.content[0] is null");
        }
        if (!CONTENT_TYPE_TOOL_USE.equals(first.type())) {
            throw responseInvalid("expected response.content[0].type=tool_use, got " + first.type());
        }
        if (!EXTRACT_TOOL_NAME.equals(first.name())) {
            throw responseInvalid("expected response.content[0].name=" + EXTRACT_TOOL_NAME + ", got " + first.name());
        }
        JsonNode input = first.input();
        if (input == null || input.isNull()) {
            throw responseInvalid("response.content[0].input is missing");
        }
        try {
            return mapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new ProviderException(
                    "PROVIDER_RESPONSE_INVALID",
                    "failed to re-serialise tool_use input: " + e.getMessage(),
                    PROVIDER_NAME,
                    OptionalInt.empty(),
                    false,
                    e);
        }
    }

    private static ProviderException responseInvalid(String detail) {
        return new ProviderException(
                "PROVIDER_RESPONSE_INVALID",
                "invalid Anthropic response: " + detail,
                PROVIDER_NAME,
                OptionalInt.empty(),
                false);
    }
}
