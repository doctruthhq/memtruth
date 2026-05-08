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
 * Contract tests for {@link AnthropicProvider}.
 *
 * <p>{@code AnthropicProvider} is a non-final concrete public class implementing
 * {@link LlmProvider}. It validates its API key in the constructor (per AGENTS.md "no silent
 * failures") and delegates {@link AnthropicProvider#complete(ProviderRequest)} to the internal
 * Anthropic HTTP client per ADR 0003.
 *
 * <p>The class is intentionally non-final so the orchestration layer (and these tests) can
 * subclass to inject canned {@link ProviderResponse} fixtures without spinning up an HTTP
 * server or vendor SDK. Real HTTP behaviour is exercised in {@code AnthropicProviderHttpTest}
 * via WireMock.
 */
class AnthropicProviderTest {

    private static final JsonNode SCHEMA = JsonNodeFactory.instance.objectNode();
    private static final ProviderOptions OPTIONS = new ProviderOptions(2, Duration.ofSeconds(30));
    private static final ProviderRequest REQUEST = new ProviderRequest("system", "user", SCHEMA, OPTIONS);

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("constructor stores the apiKey and apiKey() round-trips it")
        void apiKeyRoundTrips() {
            var provider = new AnthropicProvider("test-key");

            assertThat(provider.apiKey()).isEqualTo("test-key");
        }

        @Test
        @DisplayName("name() returns \"anthropic\"")
        void nameReturnsAnthropic() {
            var provider = new AnthropicProvider("test-key");

            assertThat(provider.name()).isEqualTo("anthropic");
        }

        @Test
        @DisplayName("two providers with different api keys are distinct objects")
        void distinctInstancesForDistinctKeys() {
            var a = new AnthropicProvider("key-a");
            var b = new AnthropicProvider("key-b");

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
                    .isThrownBy(() -> new AnthropicProvider(null))
                    .withMessageContaining("apiKey");
        }

        @Test
        @DisplayName("empty apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void emptyApiKeyThrowsIae() {
            assertThatThrownBy(() -> new AnthropicProvider(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("whitespace apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void whitespaceApiKeyThrowsIae() {
            assertThatThrownBy(() -> new AnthropicProvider("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("newline-only apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void newlineApiKeyThrowsIae() {
            assertThatThrownBy(() -> new AnthropicProvider("\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("tab-only apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void tabApiKeyThrowsIae() {
            assertThatThrownBy(() -> new AnthropicProvider("\t"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }
    }

    @Nested
    @DisplayName("input validation")
    class InputValidation {

        @Test
        @DisplayName("complete(null) throws NullPointerException mentioning \"request\" before any "
                + "HTTP call is attempted")
        void completeNullRequestThrowsNpe() {
            var provider = new AnthropicProvider("test-key");

            assertThatNullPointerException()
                    .isThrownBy(() -> provider.complete(null))
                    .withMessageContaining("request");
        }
    }

    @Nested
    @DisplayName("type")
    class Type {

        @Test
        @DisplayName("AnthropicProvider implements LlmProvider")
        void implementsLlmProvider() {
            assertThat(LlmProvider.class.isAssignableFrom(AnthropicProvider.class))
                    .isTrue();
        }

        @Test
        @DisplayName("AnthropicProvider is NOT final — orchestration agent must be able to subclass "
                + "to inject canned responses")
        void classIsNotFinal() {
            assertThat(Modifier.isFinal(AnthropicProvider.class.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("a subclass may override complete() to return a canned ProviderResponse")
        void subclassOverrideTakesEffect() throws ProviderException {
            var canned = new ProviderResponse("{\"ok\":true}", new ProviderUsage(1, 1, "test-version"));

            var provider = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest request) {
                    return canned;
                }
            };

            assertThat(provider.complete(REQUEST)).isSameAs(canned);
        }
    }
}
