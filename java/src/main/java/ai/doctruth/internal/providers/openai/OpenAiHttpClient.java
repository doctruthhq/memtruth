package ai.doctruth.internal.providers.openai;

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
import ai.doctruth.internal.providers.openai.wire.ChatCompletionsRequest;
import ai.doctruth.internal.providers.openai.wire.ChatCompletionsResponse;
import ai.doctruth.internal.providers.openai.wire.Choice;
import ai.doctruth.internal.providers.openai.wire.Message;
import ai.doctruth.internal.providers.openai.wire.ResponseFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * OpenAI Chat-Completions HTTP client. Translates the provider-neutral
 * {@link ProviderRequest} into a {@link ChatCompletionsRequest}, dispatches it via
 * {@link JsonHttpClient} under {@code RetryGate}, and re-projects the
 * {@link ChatCompletionsResponse} into a {@link ProviderResponse}.
 *
 * <p>NOT public API — see {@code package-info.java}.
 *
 * <p>Stateless and safe for concurrent use across virtual threads.
 *
 * @hidden
 */
public final class OpenAiHttpClient {

    private static final String PROVIDER_NAME = "openai";
    private final String apiKey;
    private final URI endpoint;
    private final String model;
    private final JsonHttpClient http;
    private final ai.doctruth.internal.retry.RetryGate retry;

    /** Production constructor — builds default {@link JsonHttpClient} and {@code RetryGate}. */
    public OpenAiHttpClient(String apiKey, URI endpoint, String model) {
        this(
                apiKey,
                endpoint,
                model,
                new JsonHttpClient(defaultMapper()),
                new ai.doctruth.internal.retry.RetryGate(3, Duration.ofMillis(200), Duration.ofSeconds(5)));
    }

    /** Package-private hook for tests / bench harness to inject HTTP + retry collaborators. */
    OpenAiHttpClient(
            String apiKey,
            URI endpoint,
            String model,
            JsonHttpClient http,
            ai.doctruth.internal.retry.RetryGate retry) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.model = Objects.requireNonNull(model, "model");
        this.http = Objects.requireNonNull(http, "http");
        this.retry = Objects.requireNonNull(retry, "retry");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
    }

    /**
     * Execute one Chat-Completions call. Retries transient failures per
     * {@link ProviderRequest#options()} and validates the response shape before returning.
     */
    public ProviderResponse complete(ProviderRequest request) throws ProviderException {
        Objects.requireNonNull(request, "request");
        ChatCompletionsRequest body = buildBody(request);
        Map<String, String> headers = Map.of("Authorization", "Bearer " + apiKey);
        Duration timeout = request.options().timeout();

        ChatCompletionsResponse response = retry.run(
                () -> http.post(endpoint, body, ChatCompletionsResponse.class, headers, PROVIDER_NAME, timeout),
                PROVIDER_NAME);

        return project(response);
    }

    private ChatCompletionsRequest buildBody(ProviderRequest request) {
        List<Message> messages =
                List.of(new Message("system", request.systemPrompt()), new Message("user", request.userPrompt()));
        return new ChatCompletionsRequest(model, messages, ResponseFormat.jsonSchema(request.responseSchema()));
    }

    private ProviderResponse project(ChatCompletionsResponse response) throws ProviderException {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw invalid("response had no choices");
        }
        Choice first = response.choices().get(0);
        if (first == null || first.message() == null) {
            throw invalid("response choice had no message");
        }
        String content = first.message().content();
        if (content == null || content.isBlank()) {
            throw invalid("response message content was blank");
        }
        return new ProviderResponse(content, toUsage(response));
    }

    private static ProviderUsage toUsage(ChatCompletionsResponse response) {
        int promptTokens = response.usage() == null ? 0 : response.usage().prompt_tokens();
        int completionTokens = response.usage() == null ? 0 : response.usage().completion_tokens();
        String version = response.model() == null || response.model().isBlank() ? "unknown" : response.model();
        return new ProviderUsage(promptTokens, completionTokens, version);
    }

    private static ProviderException invalid(String detail) {
        return new ProviderException(
                "PROVIDER_RESPONSE_INVALID",
                "openai response invalid: " + detail,
                PROVIDER_NAME,
                OptionalInt.empty(),
                false);
    }

    private static ObjectMapper defaultMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
