package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ProviderOptions}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code maxRetries >= 0}. Zero is valid — it means "no retry, fail fast"; useful for
 *       tests and for callers who manage retry at a higher layer.
 *   <li>{@code timeout} non-null AND strictly positive. {@link Duration#ZERO} is rejected
 *       because a zero-millisecond HTTP timeout is indistinguishable from "fail immediately"
 *       and surfaces as a confusing transport error rather than an explicit programmer error.
 *       Negative durations are also rejected with {@code IllegalArgumentException}.
 * </ul>
 *
 * <p>Per AGENTS.md "Build, don't synthesize" §4 we use {@link java.time.Duration} (JDK time)
 * rather than raw {@code long milliseconds}.
 */
class ProviderOptionsTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a typical configuration")
        void typical() {
            var options = new ProviderOptions(3, Duration.ofSeconds(30));

            assertThat(options.maxRetries()).isEqualTo(3);
            assertThat(options.timeout()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("accepts maxRetries == 0 (fail-fast / retry-disabled)")
        void zeroRetriesAllowed() {
            var options = new ProviderOptions(0, Duration.ofSeconds(5));

            assertThat(options.maxRetries()).isZero();
        }

        @Test
        @DisplayName("accepts the smallest positive timeout (1ms)")
        void oneMillisecondTimeoutAllowed() {
            var options = new ProviderOptions(0, Duration.ofMillis(1));

            assertThat(options.timeout()).isEqualTo(Duration.ofMillis(1));
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects negative maxRetries with IllegalArgumentException")
        void negativeMaxRetries() {
            assertThatThrownBy(() -> new ProviderOptions(-1, Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxRetries");
        }

        @Test
        @DisplayName("rejects null timeout with NullPointerException")
        void nullTimeout() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ProviderOptions(1, null))
                    .withMessageContaining("timeout");
        }

        @Test
        @DisplayName("rejects Duration.ZERO timeout with IllegalArgumentException")
        void zeroTimeout() {
            assertThatThrownBy(() -> new ProviderOptions(1, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout");
        }

        @Test
        @DisplayName("rejects negative-millisecond timeout with IllegalArgumentException")
        void negativeMillisTimeout() {
            assertThatThrownBy(() -> new ProviderOptions(1, Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout");
        }

        @Test
        @DisplayName("rejects negative-second timeout with IllegalArgumentException")
        void negativeSecondsTimeout() {
            assertThatThrownBy(() -> new ProviderOptions(1, Duration.ofSeconds(-5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout");
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("two options with equal fields are equal (record semantics)")
        void recordEquality() {
            var a = new ProviderOptions(2, Duration.ofSeconds(10));
            var b = new ProviderOptions(2, Duration.ofSeconds(10));

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("options with different maxRetries are not equal")
        void inequalityOnRetries() {
            var a = new ProviderOptions(2, Duration.ofSeconds(10));
            var b = new ProviderOptions(3, Duration.ofSeconds(10));

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("options with different timeouts are not equal")
        void inequalityOnTimeout() {
            var a = new ProviderOptions(2, Duration.ofSeconds(10));
            var b = new ProviderOptions(2, Duration.ofSeconds(20));

            assertThat(a).isNotEqualTo(b);
        }
    }
}
