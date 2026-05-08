package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the sealed interface {@link LlmProvider}.
 *
 * <p>{@code LlmProvider} is sealed and permits exactly four implementations, one per supported
 * vendor:
 *
 * <ul>
 *   <li>{@link AnthropicProvider}
 *   <li>{@link OpenAiProvider}
 *   <li>{@link GeminiProvider}
 *   <li>{@link DeepSeekProvider}
 * </ul>
 *
 * <p>The sealed contract is the enforcement mechanism for ADR 0003 ("hand-rolled thin LLM
 * clients on JDK HttpClient — no vendor SDKs"): consumers can pattern-match exhaustively over
 * the provider family without a {@code default} branch, so adding a fifth provider is a
 * compile-time forcing function across the codebase.
 *
 * <p>Compile-time safety: assigning a non-permitted implementation to a variable typed as
 * {@code LlmProvider} is a compile error. That property is verified by the Java compiler
 * itself and cannot be exercised at runtime.
 *
 * <p>Per ADR 0003 the public {@code LlmProvider} surface MUST NOT leak vendor-SDK types; this
 * test file therefore intentionally references only the four permitted classes by name.
 */
class LlmProviderTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("LlmProvider is a sealed interface")
        void isSealed() {
            assertThat(LlmProvider.class.isSealed()).isTrue();
            assertThat(LlmProvider.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("LlmProvider permits exactly four subtypes: "
                + "AnthropicProvider, OpenAiProvider, GeminiProvider, DeepSeekProvider")
        void permitsExactlyFourSubtypes() {
            var permitted = LlmProvider.class.getPermittedSubclasses();

            assertThat(permitted).hasSize(4);
            assertThat(permitted)
                    .containsExactlyInAnyOrder(
                            AnthropicProvider.class,
                            OpenAiProvider.class,
                            GeminiProvider.class,
                            DeepSeekProvider.class);
        }

        @Test
        @DisplayName("a switch over instanceof patterns is exhaustive across the four permitted "
                + "subtypes without a default branch")
        void exhaustiveSwitchWithoutDefault() {
            List<LlmProvider> providers = List.of(
                    new AnthropicProvider("test-key"),
                    new OpenAiProvider("test-key"),
                    new GeminiProvider("test-key"),
                    new DeepSeekProvider("test-key"));

            for (var provider : providers) {
                // Pattern-matching switch with no default — relies on the sealed contract.
                String label =
                        switch (provider) {
                            case AnthropicProvider ignored -> "anthropic";
                            case OpenAiProvider ignored -> "openai";
                            case GeminiProvider ignored -> "gemini";
                            case DeepSeekProvider ignored -> "deepseek";
                        };
                assertThat(label).isIn("anthropic", "openai", "gemini", "deepseek");
            }
        }

        @Test
        @DisplayName("LlmProvider declares a complete(ProviderRequest) method returning ProviderResponse")
        void declaresCompleteMethod() throws NoSuchMethodException {
            var method = LlmProvider.class.getMethod("complete", ProviderRequest.class);

            assertThat(method.getReturnType()).isEqualTo(ProviderResponse.class);
            assertThat(method.getExceptionTypes()).contains(ProviderException.class);
        }

        @Test
        @DisplayName("LlmProvider declares a name() method returning String (used by Provenance.model)")
        void declaresNameMethod() throws NoSuchMethodException {
            var method = LlmProvider.class.getMethod("name");

            assertThat(method.getReturnType()).isEqualTo(String.class);
            assertThat(method.getParameterCount()).isZero();
        }
    }

    @Nested
    @DisplayName("region() default method")
    class Region {

        @Test
        @DisplayName("AnthropicProvider's default region() is Optional.empty()")
        void anthropicDefaultEmpty() {
            assertThat(new AnthropicProvider("test-key").region()).isEmpty();
        }

        @Test
        @DisplayName("OpenAiProvider's default region() is Optional.empty()")
        void openaiDefaultEmpty() {
            assertThat(new OpenAiProvider("test-key").region()).isEmpty();
        }

        @Test
        @DisplayName("GeminiProvider's default region() is Optional.empty()")
        void geminiDefaultEmpty() {
            assertThat(new GeminiProvider("test-key").region()).isEmpty();
        }

        @Test
        @DisplayName("DeepSeekProvider's default region() is Optional.empty()")
        void deepseekDefaultEmpty() {
            assertThat(new DeepSeekProvider("test-key").region()).isEmpty();
        }

        @Test
        @DisplayName("an anonymous subclass overriding region() returns the override (commercial-tier"
                + " RegionEnforcingTransport plug-in shape)")
        void overrideReflected() {
            LlmProvider regionAware = new AnthropicProvider("test-key") {
                @Override
                public Optional<String> region() {
                    return Optional.of("ap-southeast-2");
                }
            };

            assertThat(regionAware.region()).contains("ap-southeast-2");
        }
    }
}
