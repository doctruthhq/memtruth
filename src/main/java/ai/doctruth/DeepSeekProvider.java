package ai.doctruth;

import java.net.URI;
import java.util.Objects;

import ai.doctruth.internal.providers.deepseek.DeepSeekHttpClient;

/**
 * DeepSeek Chat Completions provider. Backed by a hand-rolled JDK-{@code HttpClient}-based
 * client (per ADR 0003 — no vendor SDK; DeepSeek has no first-party Java SDK anyway) and
 * the Failsafe retry gate (per ADR 0004).
 *
 * <p>DeepSeek's Chat Completions endpoint is OpenAI-API-compatible at the wire level; the
 * vendor-specific wire records and HTTP plumbing live under
 * {@code ai.doctruth.internal.providers.deepseek.*} so the OpenAI and DeepSeek providers
 * can evolve independently (per AGENTS.md §1 decoupling).
 *
 * <p>{@code non-sealed} so that test code (and advanced users) can anonymously subclass to
 * supply canned responses or wrap behaviour.
 *
 * @since 0.1.0
 */
public non-sealed class DeepSeekProvider implements LlmProvider {

    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.deepseek.com/v1/chat/completions");
    private static final String DEFAULT_MODEL = "deepseek-chat";

    private final String apiKey;
    private final DeepSeekHttpClient client;

    public DeepSeekProvider(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT, DEFAULT_MODEL);
    }

    public DeepSeekProvider(String apiKey, URI endpoint, String model) {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(model, "model");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        this.apiKey = apiKey;
        this.client = new DeepSeekHttpClient(apiKey, endpoint, model);
    }

    public String apiKey() {
        return apiKey;
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public ProviderResponse complete(ProviderRequest request) throws ProviderException {
        Objects.requireNonNull(request, "request");
        return client.complete(request);
    }
}
