package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure-Java contract tests for {@link GeminiProvider} — type, invariants, and subclassing
 * affordances. HTTP behaviour (request shape, retries, error mapping) is exercised in
 * {@link GeminiProviderHttpTest} via WireMock.
 *
 * <p>The class is intentionally non-final so the orchestration layer (and these tests) can
 * subclass to inject canned {@link ProviderResponse} fixtures without spinning up an HTTP server
 * or vendor SDK; the real HTTP backend lives under
 * {@code ai.doctruth.internal.providers.gemini.*} per ADR 0003.
 */
class GeminiProviderTest {

    private static final JsonNode SCHEMA = JsonNodeFactory.instance.objectNode();
    private static final ProviderOptions OPTIONS = new ProviderOptions(2, Duration.ofSeconds(30));
    private static final ProviderRequest REQUEST = new ProviderRequest("system", "user", SCHEMA, OPTIONS);

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("constructor stores the apiKey and apiKey() round-trips it")
        void apiKeyRoundTrips() {
            var provider = new GeminiProvider("test-key");

            assertThat(provider.apiKey()).isEqualTo("test-key");
        }

        @Test
        @DisplayName("name() returns \"gemini\"")
        void nameReturnsGemini() {
            var provider = new GeminiProvider("test-key");

            assertThat(provider.name()).isEqualTo("gemini");
        }

        @Test
        @DisplayName("two providers with different api keys are distinct objects")
        void distinctInstancesForDistinctKeys() {
            var a = new GeminiProvider("key-a");
            var b = new GeminiProvider("key-b");

            assertThat(a).isNotSameAs(b);
            assertThat(a.apiKey()).isNotEqualTo(b.apiKey());
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("null apiKey throws NullPointerException mentioning \"apiKey\"")
        void nullApiKeyThrowsNpe() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GeminiProvider(null))
                    .withMessageContaining("apiKey");
        }

        @Test
        @DisplayName("empty apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void emptyApiKeyThrowsIae() {
            assertThatThrownBy(() -> new GeminiProvider(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("whitespace apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void whitespaceApiKeyThrowsIae() {
            assertThatThrownBy(() -> new GeminiProvider("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("newline-only apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void newlineApiKeyThrowsIae() {
            assertThatThrownBy(() -> new GeminiProvider("\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("tab-only apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void tabApiKeyThrowsIae() {
            assertThatThrownBy(() -> new GeminiProvider("\t"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }
    }

    @Nested
    @DisplayName("type")
    class Type {

        @Test
        @DisplayName("GeminiProvider implements LlmProvider")
        void implementsLlmProvider() {
            assertThat(LlmProvider.class.isAssignableFrom(GeminiProvider.class)).isTrue();
        }

        @Test
        @DisplayName("GeminiProvider is NOT final — orchestration agent must be able to subclass "
                + "to inject canned responses")
        void classIsNotFinal() {
            assertThat(Modifier.isFinal(GeminiProvider.class.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("a subclass may override complete() to return a canned ProviderResponse")
        void subclassOverrideTakesEffect() throws ProviderException {
            var canned = new ProviderResponse("{\"ok\":true}", new ProviderUsage(1, 1, "test-version"));

            var provider = new GeminiProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest request) {
                    return canned;
                }
            };

            assertThat(provider.complete(REQUEST)).isSameAs(canned);
        }
    }
}
