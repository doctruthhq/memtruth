package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisplayName("external LLM key live smoke")
class ExternalLlmSmokeIT {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalLlmSmokeIT.class);
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": { "partyA": { "type": "string" } },
              "required": ["partyA"],
              "additionalProperties": false
            }
            """;

    @Test
    @DisplayName("live profile can run one bounded extraction per configured provider")
    void liveProfileRunsConfiguredProvidersWithoutPrintingKeys() throws Exception {
        assumeTrue(Boolean.getBoolean("doctruth.live"), "live LLM smoke only runs under -P live");
        Map<String, String> env = loadEnv();
        Map<String, LlmProvider> providers = configuredProviders(env);
        assumeTrue(!providers.isEmpty(), "no provider keys found in environment");

        var categories = new LinkedHashMap<String, String>();
        for (Map.Entry<String, LlmProvider> entry : providers.entrySet()) {
            try {
                var result = DocTruth.from(entry.getValue())
                        .extractJson("Extract partyA from the document. Return JSON only.", JsonSchema.from(SCHEMA))
                        .withMaxRetries(1)
                        .runJson(doc());
                categories.put(
                        entry.getKey(), "ok:" + result.value().path("partyA").asText());
            } catch (ExtractionException e) {
                categories.put(entry.getKey(), "failed:" + e.errorCode());
            }
        }

        LOG.info("live provider smoke categories={}", categories);
        assertThat(categories).isNotEmpty();
    }

    private static ParsedDocument doc() {
        var location = new SourceLocation(1, 1, 1, 1, 0);
        var section = new TextSection("Contract summary. Party A: Acme Corp. Party B: Beta Ltd.", location);
        var metadata = new DocumentMetadata("live-smoke.txt", 1, Optional.empty());
        return new ParsedDocument("live-smoke", List.of(section), metadata);
    }

    private static Map<String, LlmProvider> configuredProviders(Map<String, String> env) {
        var providers = new LinkedHashMap<String, LlmProvider>();
        add(env, "ANTHROPIC_API_KEY").ifPresent(key -> providers.put("anthropic", new AnthropicProvider(key)));
        add(env, "OPENAI_API_KEY").ifPresent(key -> providers.put("openai", new OpenAiProvider(key)));
        add(env, "GEMINI_API_KEY")
                .or(() -> add(env, "GOOGLE_API_KEY"))
                .ifPresent(key -> providers.put("gemini", new GeminiProvider(key)));
        add(env, "DEEPSEEK_API_KEY").ifPresent(key -> providers.put("deepseek", new DeepSeekProvider(key)));
        return providers;
    }

    private static Optional<String> add(Map<String, String> env, String key) {
        String value = env.get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static Map<String, String> loadEnv() throws IOException {
        var merged = new LinkedHashMap<>(System.getenv());
        String configuredPath = System.getenv("DOCTRUTH_LIVE_ENV");
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path envPath = Path.of(configuredPath);
            assumeTrue(Files.isRegularFile(envPath), "DOCTRUTH_LIVE_ENV does not point to a readable file");
            for (String line : Files.readAllLines(envPath)) {
                parseEnvLine(line).ifPresent(entry -> merged.putIfAbsent(entry.getKey(), entry.getValue()));
            }
        }
        return Map.copyOf(merged);
    }

    private static Optional<Map.Entry<String, String>> parseEnvLine(String line) {
        String stripped = line.strip();
        if (stripped.isEmpty() || stripped.startsWith("#") || !stripped.contains("=")) {
            return Optional.empty();
        }
        int equals = stripped.indexOf('=');
        String key = stripped.substring(0, equals).strip();
        String value = unquote(stripped.substring(equals + 1).strip());
        return key.isBlank() ? Optional.empty() : Optional.of(Map.entry(key, value));
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
