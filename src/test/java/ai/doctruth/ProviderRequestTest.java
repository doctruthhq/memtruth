package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ProviderRequest}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code systemPrompt} non-null and non-blank — every provider call must declare a system
 *       prompt; blank strings indicate a programmer error upstream.
 *   <li>{@code userPrompt} non-null. The empty string {@code ""} IS allowed: a caller may
 *       legitimately send only the system prompt (e.g. when the entire instruction is encoded
 *       in the system role and the user turn is a no-op trigger).
 *   <li>{@code responseSchema} non-null — providers run in JSON-mode / tool-use mode only.
 *       Callers wishing to send "no schema" pass an empty object node, never null.
 *   <li>{@code options} non-null.
 * </ul>
 */
class ProviderRequestTest {

    private static final JsonNode SCHEMA = JsonNodeFactory.instance.objectNode();
    private static final ProviderOptions OPTIONS = new ProviderOptions(2, Duration.ofSeconds(30));

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a fully-populated request")
        void fullyPopulated() {
            var request =
                    new ProviderRequest("You are a careful auditor.", "Extract the contract value.", SCHEMA, OPTIONS);

            assertThat(request.systemPrompt()).isEqualTo("You are a careful auditor.");
            assertThat(request.userPrompt()).isEqualTo("Extract the contract value.");
            assertThat(request.responseSchema()).isSameAs(SCHEMA);
            assertThat(request.options()).isSameAs(OPTIONS);
        }

        @Test
        @DisplayName("accepts an empty userPrompt — caller may rely entirely on the system prompt")
        void emptyUserPromptAllowed() {
            var request = new ProviderRequest("system", "", SCHEMA, OPTIONS);

            assertThat(request.userPrompt()).isEmpty();
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null systemPrompt with NullPointerException")
        void nullSystemPrompt() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ProviderRequest(null, "user", SCHEMA, OPTIONS))
                    .withMessageContaining("systemPrompt");
        }

        @Test
        @DisplayName("rejects empty systemPrompt with IllegalArgumentException")
        void emptySystemPrompt() {
            assertThatThrownBy(() -> new ProviderRequest("", "user", SCHEMA, OPTIONS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("systemPrompt");
        }

        @Test
        @DisplayName("rejects whitespace-only systemPrompt with IllegalArgumentException")
        void whitespaceSystemPrompt() {
            assertThatThrownBy(() -> new ProviderRequest("  ", "user", SCHEMA, OPTIONS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("systemPrompt");
        }

        @Test
        @DisplayName("rejects newline-only systemPrompt with IllegalArgumentException")
        void newlineSystemPrompt() {
            assertThatThrownBy(() -> new ProviderRequest("\n", "user", SCHEMA, OPTIONS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("systemPrompt");
        }

        @Test
        @DisplayName("rejects tab-only systemPrompt with IllegalArgumentException")
        void tabSystemPrompt() {
            assertThatThrownBy(() -> new ProviderRequest("\t", "user", SCHEMA, OPTIONS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("systemPrompt");
        }

        @Test
        @DisplayName("rejects null userPrompt with NullPointerException")
        void nullUserPrompt() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ProviderRequest("system", null, SCHEMA, OPTIONS))
                    .withMessageContaining("userPrompt");
        }

        @Test
        @DisplayName("rejects null responseSchema with NullPointerException")
        void nullResponseSchema() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ProviderRequest("system", "user", null, OPTIONS))
                    .withMessageContaining("responseSchema");
        }

        @Test
        @DisplayName("rejects null options with NullPointerException")
        void nullOptions() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ProviderRequest("system", "user", SCHEMA, null))
                    .withMessageContaining("options");
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("two requests with equal fields are equal (record semantics)")
        void recordEquality() {
            var a = new ProviderRequest("system", "user", SCHEMA, OPTIONS);
            var b = new ProviderRequest("system", "user", SCHEMA, OPTIONS);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("requests with different prompts are not equal")
        void inequalityOnPromptDifference() {
            var a = new ProviderRequest("system", "user-a", SCHEMA, OPTIONS);
            var b = new ProviderRequest("system", "user-b", SCHEMA, OPTIONS);

            assertThat(a).isNotEqualTo(b);
        }
    }
}
