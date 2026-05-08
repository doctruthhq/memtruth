package ai.doctruth.internal.providers.deepseek;

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
import ai.doctruth.internal.providers.deepseek.wire.ChatCompletionsRequest;
import ai.doctruth.internal.providers.deepseek.wire.ChatCompletionsResponse;
import ai.doctruth.internal.providers.deepseek.wire.Choice;
import ai.doctruth.internal.providers.deepseek.wire.Message;
import ai.doctruth.internal.providers.deepseek.wire.ResponseFormat;
import ai.doctruth.internal.retry.RetryGate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DeepSeek Chat Completions HTTP client. Single responsibility: translate a public
 * {@link ProviderRequest} into the DeepSeek wire format, POST it via {@link JsonHttpClient},
 * translate the response into a public {@link ProviderResponse}. Retries are delegated to
 * {@link RetryGate}.
 *
 * <p>NOT public API.
 *
 * <p>DeepSeek's Chat Completions API is OpenAI-compatible at the wire level; we deliberately
 * do not share wire records with the OpenAI provider so each provider can evolve
 * independently (per AGENTS.md §1 decoupling).
 *
 * @hidden
 */
public final class DeepSeekHttpClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekHttpClient.class);

    private static final String PROVIDER_NAME = "deepseek";
    private static final String JSON_OBJECT = "json_object";
    private static final Duration RETRY_INITIAL_DELAY = Duration.ofMillis(200);
    private static final Duration RETRY_MAX_DELAY = Duration.ofSeconds(8);

    private final String apiKey;
    private final URI endpoint;
    private final String model;
    private final JsonHttpClient http;
    private final RetryGate fixedRetry;

    /** Production constructor — wires a default {@link JsonHttpClient} and per-call retry. */
    public DeepSeekHttpClient(String apiKey, URI endpoint, String model) {
        this(apiKey, endpoint, model, new JsonHttpClient(new ObjectMapper()), null);
    }

    /**
     * Package-private hook for tests to inject a stubbed HTTP client and a fixed retry gate.
     * When {@code retry} is {@code null}, a fresh per-call gate is built from
     * {@link ai.doctruth.ProviderOptions#maxRetries()}.
     */
    DeepSeekHttpClient(String apiKey, URI endpoint, String model, JsonHttpClient http, RetryGate retry) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.model = Objects.requireNonNull(model, "model");
        this.http = Objects.requireNonNull(http, "http");
        this.fixedRetry = retry;
    }

    public ProviderResponse complete(ProviderRequest request) throws ProviderException {
        Objects.requireNonNull(request, "request");
        log.debug("provider={} model={} sending chat-completions request", PROVIDER_NAME, model);
        RetryGate gate = fixedRetry != null ? fixedRetry : buildDefaultGate(request);
        return gate.run(() -> sendOnce(request), PROVIDER_NAME);
    }

    private ProviderResponse sendOnce(ProviderRequest request) throws ProviderException {
        ChatCompletionsRequest body = buildRequestBody(request);
        Map<String, String> headers = Map.of("Authorization", "Bearer " + apiKey);
        ChatCompletionsResponse response = http.post(
                endpoint,
                body,
                ChatCompletionsResponse.class,
                headers,
                PROVIDER_NAME,
                request.options().timeout());
        return toProviderResponse(response);
    }

    private ChatCompletionsRequest buildRequestBody(ProviderRequest request) {
        List<Message> messages =
                List.of(new Message("system", request.systemPrompt()), new Message("user", request.userPrompt()));
        return new ChatCompletionsRequest(model, messages, new ResponseFormat(JSON_OBJECT));
    }

    private static ProviderResponse toProviderResponse(ChatCompletionsResponse response) throws ProviderException {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw invalidResponse("DeepSeek response had no choices");
        }
        Choice first = response.choices().get(0);
        if (first == null || first.message() == null || first.message().content() == null) {
            throw invalidResponse("DeepSeek response choice had no message content");
        }
        String content = first.message().content();
        if (content.isBlank()) {
            throw invalidResponse("DeepSeek response message content was blank");
        }
        String modelVersion = response.model();
        if (modelVersion == null || modelVersion.isBlank()) {
            throw invalidResponse("DeepSeek response had no model field");
        }
        return new ProviderResponse(content, toUsage(response, modelVersion));
    }

    private static ProviderUsage toUsage(ChatCompletionsResponse response, String modelVersion) {
        if (response.usage() == null) {
            return new ProviderUsage(0, 0, modelVersion);
        }
        return new ProviderUsage(
                response.usage().prompt_tokens(), response.usage().completion_tokens(), modelVersion);
    }

    private static ProviderException invalidResponse(String message) {
        return new ProviderException(
                "PROVIDER_RESPONSE_INVALID",
                message + " for provider=" + PROVIDER_NAME,
                PROVIDER_NAME,
                OptionalInt.empty(),
                false);
    }

    private static RetryGate buildDefaultGate(ProviderRequest request) {
        return new RetryGate(request.options().maxRetries(), RETRY_INITIAL_DELAY, RETRY_MAX_DELAY);
    }
}
