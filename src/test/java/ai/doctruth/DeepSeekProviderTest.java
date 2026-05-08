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
 * Contract tests for {@link DeepSeekProvider} — public-shape invariants only. The
 * HTTP-backed behaviour (request body, headers, retries, response parsing) is exercised
 * by {@code DeepSeekProviderHttpTest} via WireMock so this file stays free of any wire
 * concerns and runs in &lt; 50 ms.
 *
 * <p>The class is intentionally non-final so test code (and advanced users) can subclass
 * to inject canned {@link ProviderResponse} fixtures; the real HTTP backend lives under
 * {@code ai.doctruth.internal.providers.deepseek.*} per ADR 0003. DeepSeek has no first-
 * party Java SDK, which makes the hand-rolled stance especially natural for this provider.
 */
class DeepSeekProviderTest {

    private static final JsonNode SCHEMA = JsonNodeFactory.instance.objectNode();
    private static final ProviderOptions OPTIONS = new ProviderOptions(2, Duration.ofSeconds(30));
    private static final ProviderRequest REQUEST = new ProviderRequest("system", "user", SCHEMA, OPTIONS);

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("constructor stores the apiKey and apiKey() round-trips it")
        void apiKeyRoundTrips() {
            var provider = new DeepSeekProvider("test-key");

            assertThat(provider.apiKey()).isEqualTo("test-key");
        }

        @Test
        @DisplayName("name() returns \"deepseek\"")
        void nameReturnsDeepSeek() {
            var provider = new DeepSeekProvider("test-key");

            assertThat(provider.name()).isEqualTo("deepseek");
        }

        @Test
        @DisplayName("two providers with different api keys are distinct objects")
        void distinctInstancesForDistinctKeys() {
            var a = new DeepSeekProvider("key-a");
            var b = new DeepSeekProvider("key-b");

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
                    .isThrownBy(() -> new DeepSeekProvider(null))
                    .withMessageContaining("apiKey");
        }

        @Test
        @DisplayName("empty apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void emptyApiKeyThrowsIae() {
            assertThatThrownBy(() -> new DeepSeekProvider(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("whitespace apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void whitespaceApiKeyThrowsIae() {
            assertThatThrownBy(() -> new DeepSeekProvider("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("newline-only apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void newlineApiKeyThrowsIae() {
            assertThatThrownBy(() -> new DeepSeekProvider("\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("tab-only apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void tabApiKeyThrowsIae() {
            assertThatThrownBy(() -> new DeepSeekProvider("\t"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }
    }

    @Nested
    @DisplayName("type")
    class Type {

        @Test
        @DisplayName("DeepSeekProvider implements LlmProvider")
        void implementsLlmProvider() {
            assertThat(LlmProvider.class.isAssignableFrom(DeepSeekProvider.class))
                    .isTrue();
        }

        @Test
        @DisplayName("DeepSeekProvider is NOT final — orchestration agent must be able to subclass "
                + "to inject canned responses")
        void classIsNotFinal() {
            assertThat(Modifier.isFinal(DeepSeekProvider.class.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("a subclass may override complete() to return a canned ProviderResponse")
        void subclassOverrideTakesEffect() throws ProviderException {
            var canned = new ProviderResponse("{\"ok\":true}", new ProviderUsage(1, 1, "test-version"));

            var provider = new DeepSeekProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest request) {
                    return canned;
                }
            };

            assertThat(provider.complete(REQUEST)).isSameAs(canned);
        }
    }
}
