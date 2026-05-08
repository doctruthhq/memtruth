package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ProviderUsage}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code inputTokens >= 0}. Zero is valid — some providers report zero for cached / no-op
 *       turns; the library faithfully passes that through rather than fabricating a value.
 *   <li>{@code outputTokens >= 0}. Zero is valid for early-stop / refusal responses.
 *   <li>{@code modelVersion} non-null and non-blank — the version string is part of the audit
 *       trail (mirrors {@link Provenance#modelVersion()}). Without it, downstream callers
 *       cannot reproduce or compare extractions.
 * </ul>
 */
class ProviderUsageTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts typical token counts")
        void typical() {
            var usage = new ProviderUsage(1_024, 256, "claude-sonnet-4-7-20260301");

            assertThat(usage.inputTokens()).isEqualTo(1_024);
            assertThat(usage.outputTokens()).isEqualTo(256);
            assertThat(usage.modelVersion()).isEqualTo("claude-sonnet-4-7-20260301");
        }

        @Test
        @DisplayName("accepts inputTokens == 0 (e.g. fully-cached turn)")
        void zeroInputTokens() {
            var usage = new ProviderUsage(0, 50, "claude-sonnet-4-7-20260301");

            assertThat(usage.inputTokens()).isZero();
        }

        @Test
        @DisplayName("accepts outputTokens == 0 (e.g. early-stop / refusal)")
        void zeroOutputTokens() {
            var usage = new ProviderUsage(100, 0, "claude-sonnet-4-7-20260301");

            assertThat(usage.outputTokens()).isZero();
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects negative inputTokens with IllegalArgumentException")
        void negativeInputTokens() {
            assertThatThrownBy(() -> new ProviderUsage(-1, 0, "v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("inputTokens");
        }

        @Test
        @DisplayName("rejects negative outputTokens with IllegalArgumentException")
        void negativeOutputTokens() {
            assertThatThrownBy(() -> new ProviderUsage(0, -1, "v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outputTokens");
        }

        @Test
        @DisplayName("rejects null modelVersion with NullPointerException")
        void nullModelVersion() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ProviderUsage(0, 0, null))
                    .withMessageContaining("modelVersion");
        }

        @Test
        @DisplayName("rejects empty modelVersion with IllegalArgumentException")
        void emptyModelVersion() {
            assertThatThrownBy(() -> new ProviderUsage(0, 0, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("modelVersion");
        }

        @Test
        @DisplayName("rejects whitespace-only modelVersion with IllegalArgumentException")
        void whitespaceModelVersion() {
            assertThatThrownBy(() -> new ProviderUsage(0, 0, "   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("modelVersion");
        }

        @Test
        @DisplayName("rejects newline-only modelVersion with IllegalArgumentException")
        void newlineModelVersion() {
            assertThatThrownBy(() -> new ProviderUsage(0, 0, "\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("modelVersion");
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("two usages with equal fields are equal (record semantics)")
        void recordEquality() {
            var a = new ProviderUsage(100, 50, "v1");
            var b = new ProviderUsage(100, 50, "v1");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("usages with different token counts are not equal")
        void inequalityOnTokens() {
            var a = new ProviderUsage(100, 50, "v1");
            var b = new ProviderUsage(101, 50, "v1");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("usages with different modelVersion are not equal")
        void inequalityOnModelVersion() {
            var a = new ProviderUsage(100, 50, "v1");
            var b = new ProviderUsage(100, 50, "v2");

            assertThat(a).isNotEqualTo(b);
        }
    }
}
