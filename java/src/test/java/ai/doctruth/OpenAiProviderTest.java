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
 * Contract tests for {@link OpenAiProvider} — public-shape only. The HTTP behaviour lives in
 * {@code OpenAiProviderHttpTest} (WireMock-backed). These tests stay framework-free so that
 * the public surface (constructor invariants, {@code name()}, subclassability) can be
 * exercised without the network at all.
 */
class OpenAiProviderTest {

    private static final JsonNode SCHEMA = JsonNodeFactory.instance.objectNode();
    private static final ProviderOptions OPTIONS = new ProviderOptions(2, Duration.ofSeconds(30));
    private static final ProviderRequest REQUEST = new ProviderRequest("system", "user", SCHEMA, OPTIONS);

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("constructor stores the apiKey and apiKey() round-trips it")
        void apiKeyRoundTrips() {
            var provider = new OpenAiProvider("test-key");

            assertThat(provider.apiKey()).isEqualTo("test-key");
        }

        @Test
        @DisplayName("name() returns \"openai\"")
        void nameReturnsOpenAi() {
            var provider = new OpenAiProvider("test-key");

            assertThat(provider.name()).isEqualTo("openai");
        }

        @Test
        @DisplayName("two providers with different api keys are distinct objects")
        void distinctInstancesForDistinctKeys() {
            var a = new OpenAiProvider("key-a");
            var b = new OpenAiProvider("key-b");

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
                    .isThrownBy(() -> new OpenAiProvider(null))
                    .withMessageContaining("apiKey");
        }

        @Test
        @DisplayName("empty apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void emptyApiKeyThrowsIae() {
            assertThatThrownBy(() -> new OpenAiProvider(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("whitespace apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void whitespaceApiKeyThrowsIae() {
            assertThatThrownBy(() -> new OpenAiProvider("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("newline-only apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void newlineApiKeyThrowsIae() {
            assertThatThrownBy(() -> new OpenAiProvider("\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("tab-only apiKey throws IllegalArgumentException mentioning \"apiKey\"")
        void tabApiKeyThrowsIae() {
            assertThatThrownBy(() -> new OpenAiProvider("\t"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("complete(null) throws NullPointerException mentioning \"request\"")
        void completeNullRequestThrowsNpe() {
            var provider = new OpenAiProvider("test-key");

            assertThatNullPointerException()
                    .isThrownBy(() -> provider.complete(null))
                    .withMessageContaining("request");
        }
    }

    @Nested
    @DisplayName("type")
    class Type {

        @Test
        @DisplayName("OpenAiProvider implements LlmProvider")
        void implementsLlmProvider() {
            assertThat(LlmProvider.class.isAssignableFrom(OpenAiProvider.class)).isTrue();
        }

        @Test
        @DisplayName("OpenAiProvider is NOT final — orchestration agent must be able to subclass "
                + "to inject canned responses")
        void classIsNotFinal() {
            assertThat(Modifier.isFinal(OpenAiProvider.class.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("a subclass may override complete() to return a canned ProviderResponse")
        void subclassOverrideTakesEffect() throws ProviderException {
            var canned = new ProviderResponse("{\"ok\":true}", new ProviderUsage(1, 1, "test-version"));

            var provider = new OpenAiProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest request) {
                    return canned;
                }
            };

            assertThat(provider.complete(REQUEST)).isSameAs(canned);
        }
    }
}
