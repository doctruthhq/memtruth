package ai.doctruth;

import java.net.URI;
import java.util.Objects;

import ai.doctruth.internal.providers.anthropic.AnthropicHttpClient;

/**
 * Anthropic Messages API provider. Hand-rolled JDK-{@code HttpClient}-backed implementation
 * per ADR 0003 — no Anthropic SDK on the classpath. Vendor wire types are confined to
 * {@link ai.doctruth.internal.providers.anthropic} and never appear in this class's public
 * surface.
 *
 * <p>{@code non-sealed} so that test code (and advanced users) can anonymously subclass to
 * supply canned responses or wrap behaviour. Public API is thread-safe: the underlying
 * {@link AnthropicHttpClient} is stateless and shares one {@link java.net.http.HttpClient}.
 *
 * @since 0.1.0
 */
public non-sealed class AnthropicProvider implements LlmProvider {

    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final String DEFAULT_MODEL = "claude-sonnet-4-5";

    private final String apiKey;
    private final AnthropicHttpClient client;

    /**
     * Build a provider against the public Anthropic endpoint and {@value #DEFAULT_MODEL}.
     *
     * @param apiKey non-blank Anthropic API key.
     */
    public AnthropicProvider(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT, DEFAULT_MODEL);
    }

    /**
     * Build a provider against an explicit endpoint and model name. Required for users on
     * Bedrock / Vertex Anthropic-compat endpoints, in-cluster proxies, or test harnesses.
     *
     * @param apiKey   non-blank Anthropic API key.
     * @param endpoint full Messages-API URL (typically ending in {@code /v1/messages}).
     * @param model    Anthropic model identifier (e.g. {@code "claude-sonnet-4-5"}).
     */
    public AnthropicProvider(String apiKey, URI endpoint, String model) {
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
        this.client = new AnthropicHttpClient(apiKey, endpoint, model);
    }

    public String apiKey() {
        return apiKey;
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public ProviderResponse complete(ProviderRequest request) throws ProviderException {
        Objects.requireNonNull(request, "request");
        return client.complete(request);
    }
}
