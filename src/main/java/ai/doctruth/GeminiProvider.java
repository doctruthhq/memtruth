package ai.doctruth;

import java.net.URI;
import java.util.Objects;

import ai.doctruth.internal.providers.gemini.GeminiHttpClient;

/**
 * Google Gemini {@code generateContent} provider. Per ADR 0003 the vendor SDK is NOT on the
 * classpath; this class is a thin facade that delegates to a hand-rolled
 * {@link GeminiHttpClient} (JDK {@code HttpClient} + Jackson + Failsafe via
 * {@link ai.doctruth.internal.retry.RetryGate}).
 *
 * <p>{@code non-sealed} so test code (and advanced users) can anonymously subclass to
 * supply canned responses or wrap behaviour.
 *
 * @since 0.1.0
 */
public non-sealed class GeminiProvider implements LlmProvider {

    /** Default base URL for the public Generative Language API. */
    private static final URI DEFAULT_ENDPOINT = URI.create("https://generativelanguage.googleapis.com");

    /** Default model id. Updated per release; not a public-API contract. */
    private static final String DEFAULT_MODEL = "gemini-1.5-pro";

    private final String apiKey;
    private final GeminiHttpClient client;

    /** Production constructor — talks to {@code generativelanguage.googleapis.com}. */
    public GeminiProvider(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT, DEFAULT_MODEL);
    }

    /**
     * Test / advanced-user constructor: point at an alternative base URL (e.g. WireMock) or
     * pin a specific model id.
     *
     * @param apiKey   Gemini API key; non-null and non-blank.
     * @param endpoint base URL for the API (the client appends
     *     {@code /v1beta/models/{model}:generateContent}); non-null.
     * @param model    Gemini model id; non-null and non-blank.
     */
    public GeminiProvider(String apiKey, URI endpoint, String model) {
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
        this.client = new GeminiHttpClient(apiKey, endpoint, model);
    }

    public String apiKey() {
        return apiKey;
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public ProviderResponse complete(ProviderRequest request) throws ProviderException {
        Objects.requireNonNull(request, "request");
        return client.complete(request);
    }
}
