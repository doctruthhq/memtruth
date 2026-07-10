package ai.doctruth;

import java.net.URI;
import java.util.Objects;

/**
 * Small provider factory for the common SDK path. Use these helpers with
 * {@link DocTruth#withProvider(LlmProvider)} when application code should not directly
 * construct provider classes. The methods intentionally accept explicit keys so production
 * applications can source secrets from their own configuration layer.
 *
 * @since 0.2.0
 */
public final class LlmProviders {

    private LlmProviders() {}

    public static OpenAiProvider openAi(String apiKey) {
        return new OpenAiProvider(apiKey);
    }

    public static OpenAiProvider openAiCompatible(String apiKey, URI endpoint, String model) {
        Objects.requireNonNull(endpoint, "endpoint");
        return new OpenAiProvider(apiKey, endpoint, model);
    }

    public static AnthropicProvider anthropic(String apiKey) {
        return new AnthropicProvider(apiKey);
    }

    public static GeminiProvider gemini(String apiKey) {
        return new GeminiProvider(apiKey);
    }

    public static DeepSeekProvider deepSeek(String apiKey) {
        return new DeepSeekProvider(apiKey);
    }
}
