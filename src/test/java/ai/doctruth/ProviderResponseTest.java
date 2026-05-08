package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ProviderResponse}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code rawJson} non-null and non-blank — every successful provider call returns at least
 *       one byte of JSON. An empty body is a transport-layer failure that should surface as
 *       {@link ProviderException}, never as a {@code ProviderResponse}.
 *   <li>{@code usage} non-null — token accounting is mandatory for cost-attribution and
 *       observability per AGENTS.md "Auditable + debuggable + loggable everywhere".
 * </ul>
 */
class ProviderResponseTest {

    private static final ProviderUsage USAGE = new ProviderUsage(120, 80, "claude-sonnet-4-7-20260301");

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a fully-populated response")
        void fullyPopulated() {
            var response = new ProviderResponse("{\"value\":42}", USAGE);

            assertThat(response.rawJson()).isEqualTo("{\"value\":42}");
            assertThat(response.usage()).isSameAs(USAGE);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null rawJson with NullPointerException")
        void nullRawJson() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ProviderResponse(null, USAGE))
                    .withMessageContaining("rawJson");
        }

        @Test
        @DisplayName("rejects empty rawJson with IllegalArgumentException")
        void emptyRawJson() {
            assertThatThrownBy(() -> new ProviderResponse("", USAGE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rawJson");
        }

        @Test
        @DisplayName("rejects whitespace-only rawJson with IllegalArgumentException")
        void whitespaceRawJson() {
            assertThatThrownBy(() -> new ProviderResponse("   ", USAGE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rawJson");
        }

        @Test
        @DisplayName("rejects newline-only rawJson with IllegalArgumentException")
        void newlineRawJson() {
            assertThatThrownBy(() -> new ProviderResponse("\n", USAGE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rawJson");
        }

        @Test
        @DisplayName("rejects null usage with NullPointerException")
        void nullUsage() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ProviderResponse("{}", null))
                    .withMessageContaining("usage");
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("two responses with equal fields are equal (record semantics)")
        void recordEquality() {
            var a = new ProviderResponse("{\"x\":1}", USAGE);
            var b = new ProviderResponse("{\"x\":1}", USAGE);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("responses with different rawJson are not equal")
        void inequalityOnRawJsonDifference() {
            var a = new ProviderResponse("{\"x\":1}", USAGE);
            var b = new ProviderResponse("{\"x\":2}", USAGE);

            assertThat(a).isNotEqualTo(b);
        }
    }
}
