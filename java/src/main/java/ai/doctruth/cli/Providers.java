package ai.doctruth.cli;

import java.net.URI;
import java.util.Map;

import ai.doctruth.AnthropicProvider;
import ai.doctruth.DeepSeekProvider;
import ai.doctruth.GeminiProvider;
import ai.doctruth.LlmProvider;
import ai.doctruth.OpenAiProvider;

final class Providers {

    private static final URI OPENAI_ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");
    private static final URI ANTHROPIC_ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final URI GEMINI_ENDPOINT = URI.create("https://generativelanguage.googleapis.com");
    private static final URI DEEPSEEK_ENDPOINT = URI.create("https://api.deepseek.com/v1/chat/completions");

    private Providers() {
        throw new AssertionError("no instances");
    }

    static LlmProvider create(ProviderConfig options) throws CliException {
        String provider = options.provider().toLowerCase();
        return switch (provider) {
            case "openai" -> openAi(options);
            case "anthropic" -> anthropic(options);
            case "gemini" -> gemini(options);
            case "deepseek" -> deepSeek(options);
            default -> throw new CliException("unsupported provider: " + options.provider());
        };
    }

    private static LlmProvider openAi(ProviderConfig options) throws CliException {
        String key = requireKey(options.config().env(), "OPENAI_API_KEY");
        URI endpoint = options.baseUrl().map(Providers::openAiEndpoint).orElse(OPENAI_ENDPOINT);
        return new OpenAiProvider(key, endpoint, options.effectiveModel("gpt-4o"));
    }

    private static LlmProvider anthropic(ProviderConfig options) throws CliException {
        String key = requireKey(options.config().env(), "ANTHROPIC_API_KEY");
        return new AnthropicProvider(
                key, options.baseUrl().orElse(ANTHROPIC_ENDPOINT), options.effectiveModel("claude-sonnet-4-5"));
    }

    private static LlmProvider gemini(ProviderConfig options) throws CliException {
        String key = requireKey(options.config().env(), "GOOGLE_API_KEY");
        return new GeminiProvider(
                key, options.baseUrl().orElse(GEMINI_ENDPOINT), options.effectiveModel("gemini-1.5-pro"));
    }

    private static LlmProvider deepSeek(ProviderConfig options) throws CliException {
        String key = requireKey(options.config().env(), "DEEPSEEK_API_KEY");
        return new DeepSeekProvider(
                key, options.baseUrl().orElse(DEEPSEEK_ENDPOINT), options.effectiveModel("deepseek-chat"));
    }

    private static URI openAiEndpoint(URI base) {
        String value = base.toString();
        if (value.endsWith("/chat/completions")) {
            return base;
        }
        return URI.create(value.replaceAll("/+$", "") + "/chat/completions");
    }

    private static String requireKey(Map<String, String> env, String name) throws CliException {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new CliException("missing " + name + "; set it with: export " + name + "=...");
        }
        return value;
    }
}
