package ai.doctruth;

import java.net.URI;
import java.util.Objects;

import ai.doctruth.internal.providers.openai.OpenAiHttpClient;

/**
 * OpenAI Chat-Completions API provider. Delegates HTTP / retry to
 * {@code ai.doctruth.internal.providers.openai.OpenAiHttpClient} (per ADR 0003) so the
 * public surface stays free of vendor wire types.
 *
 * <p>{@code non-sealed} so that test code (and advanced users) can anonymously subclass to
 * supply canned responses or wrap behaviour.
 *
 * @since 0.1.0
 */
public non-sealed class OpenAiProvider implements LlmProvider {

    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");
    private static final String DEFAULT_MODEL = "gpt-4o";

    private final String apiKey;
    private final URI endpoint;
    private final String model;
    private final OpenAiHttpClient client;

    /** Standard constructor — points at the public OpenAI endpoint with the default model. */
    public OpenAiProvider(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT, DEFAULT_MODEL);
    }

    /**
     * Constructor for callers who need to override the endpoint (Azure-OpenAI / proxy /
     * recorded WireMock) or pin a specific model name.
     */
    public OpenAiProvider(String apiKey, URI endpoint, String model) {
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
        this.endpoint = endpoint;
        this.model = model;
        this.client = new OpenAiHttpClient(apiKey, endpoint, model);
    }

    public String apiKey() {
        return apiKey;
    }

    public URI endpoint() {
        return endpoint;
    }

    public String model() {
        return model;
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public ProviderResponse complete(ProviderRequest request) throws ProviderException {
        Objects.requireNonNull(request, "request");
        return client.complete(request);
    }
}
