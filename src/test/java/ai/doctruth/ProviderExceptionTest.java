package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ProviderException}.
 *
 * <p>Per AGENTS.md "Engineering principles" §2 (Auditable + debuggable + loggable everywhere):
 * an LLM-provider failure must surface (a) which provider failed, (b) the upstream HTTP status
 * if the parser saw one, and (c) whether the caller may safely retry — so that retry / circuit
 * breaker logic upstream can act deterministically. Per AGENTS.md "Code style + conventions" —
 * Error handling: this is a CHECKED exception extending {@link Exception} directly.
 *
 * <p>Invariants this exception must enforce:
 *
 * <ul>
 *   <li>{@code errorCode} is non-null and non-blank.
 *   <li>{@code message} is non-null.
 *   <li>{@code providerName} is non-null and non-blank.
 *   <li>{@code httpStatus} is an {@link OptionalInt}; the {@code OptionalInt} itself is non-null.
 *       Use {@link OptionalInt#empty()} when no HTTP status is available (e.g. network refused).
 *   <li>{@code retryable} is a primitive boolean — both values are valid.
 *   <li>{@code cause} is optional.
 * </ul>
 *
 * <p>Per AGENTS.md "Engineering principles" §4 (Build, don't synthesize): we use the JDK-idiomatic
 * primitive {@link OptionalInt} rather than {@code Optional<Integer>}.
 */
class ProviderExceptionTest {

    private static final String ERROR_CODE = "PROVIDER_RATE_LIMITED";
    private static final String MESSAGE = "anthropic returned 429 — rate limit exceeded";
    private static final String PROVIDER_NAME = "anthropic";

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("5-arg constructor (no cause): all six accessors round-trip and getCause() is null")
        void fiveArgConstructorRoundTrip() {
            var ex = new ProviderException(ERROR_CODE, MESSAGE, PROVIDER_NAME, OptionalInt.of(429), true);

            assertThat(ex.getMessage()).isEqualTo(MESSAGE);
            assertThat(ex.errorCode()).isEqualTo(ERROR_CODE);
            assertThat(ex.providerName()).isEqualTo(PROVIDER_NAME);
            assertThat(ex.httpStatus()).hasValue(429);
            assertThat(ex.retryable()).isTrue();
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName(
                "6-arg constructor (with cause): all six accessors round-trip and getCause() returns the supplied throwable")
        void sixArgConstructorRoundTrip() {
            var sentinel = new IllegalStateException("sentinel");
            var ex = new ProviderException(ERROR_CODE, MESSAGE, PROVIDER_NAME, OptionalInt.of(503), true, sentinel);

            assertThat(ex.getMessage()).isEqualTo(MESSAGE);
            assertThat(ex.errorCode()).isEqualTo(ERROR_CODE);
            assertThat(ex.providerName()).isEqualTo(PROVIDER_NAME);
            assertThat(ex.httpStatus()).hasValue(503);
            assertThat(ex.retryable()).isTrue();
            assertThat(ex.getCause()).isSameAs(sentinel);
        }

        @Test
        @DisplayName("OptionalInt.empty() httpStatus round-trips (no HTTP response observed)")
        void emptyHttpStatusRoundTrip() {
            var ex = new ProviderException(ERROR_CODE, MESSAGE, PROVIDER_NAME, OptionalInt.empty(), false);

            assertThat(ex.httpStatus()).isEmpty();
        }

        @Test
        @DisplayName("OptionalInt.of(429) httpStatus round-trips")
        void presentHttpStatusRoundTrip() {
            var ex = new ProviderException(ERROR_CODE, MESSAGE, PROVIDER_NAME, OptionalInt.of(429), true);

            assertThat(ex.httpStatus()).hasValue(429);
        }

        @Test
        @DisplayName("retryable == true round-trips")
        void retryableTrueRoundTrip() {
            var ex = new ProviderException(ERROR_CODE, MESSAGE, PROVIDER_NAME, OptionalInt.of(503), true);

            assertThat(ex.retryable()).isTrue();
        }

        @Test
        @DisplayName("retryable == false round-trips (e.g. 401 — caller must not retry)")
        void retryableFalseRoundTrip() {
            var ex = new ProviderException(
                    "PROVIDER_AUTH_FAILED", "401 unauthorized", PROVIDER_NAME, OptionalInt.of(401), false);

            assertThat(ex.retryable()).isFalse();
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null errorCode with NullPointerException")
        void nullErrorCode() {
            assertThatThrownBy(() -> new ProviderException(null, MESSAGE, PROVIDER_NAME, OptionalInt.empty(), false))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("errorCode");
        }

        @Test
        @DisplayName("rejects empty errorCode with IllegalArgumentException")
        void emptyErrorCode() {
            assertThatThrownBy(() -> new ProviderException("", MESSAGE, PROVIDER_NAME, OptionalInt.empty(), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("errorCode");
        }

        @Test
        @DisplayName("rejects whitespace-only errorCode with IllegalArgumentException")
        void blankErrorCode() {
            assertThatThrownBy(() -> new ProviderException("   ", MESSAGE, PROVIDER_NAME, OptionalInt.empty(), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("errorCode");
        }

        @Test
        @DisplayName("rejects null message with NullPointerException")
        void nullMessage() {
            assertThatThrownBy(() -> new ProviderException(ERROR_CODE, null, PROVIDER_NAME, OptionalInt.empty(), false))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("message");
        }

        @Test
        @DisplayName("rejects null providerName with NullPointerException")
        void nullProviderName() {
            assertThatThrownBy(() -> new ProviderException(ERROR_CODE, MESSAGE, null, OptionalInt.empty(), false))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("providerName");
        }

        @Test
        @DisplayName("rejects empty providerName with IllegalArgumentException")
        void emptyProviderName() {
            assertThatThrownBy(() -> new ProviderException(ERROR_CODE, MESSAGE, "", OptionalInt.empty(), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("providerName");
        }

        @Test
        @DisplayName("rejects whitespace-only providerName with IllegalArgumentException")
        void blankProviderName() {
            assertThatThrownBy(() -> new ProviderException(ERROR_CODE, MESSAGE, "   ", OptionalInt.empty(), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("providerName");
        }

        @Test
        @DisplayName(
                "rejects null httpStatus Optional with NullPointerException (callers must use OptionalInt.empty())")
        void nullHttpStatusOptional() {
            assertThatThrownBy(() -> new ProviderException(ERROR_CODE, MESSAGE, PROVIDER_NAME, null, false))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("httpStatus");
        }
    }

    @Nested
    @DisplayName("cause")
    class Cause {

        @Test
        @DisplayName("6-arg ctor preserves cause via getCause() (sentinel identity preserved)")
        void preservesCauseIdentity() {
            var sentinel = new IOException("sentinel-cause");
            var ex = new ProviderException(ERROR_CODE, MESSAGE, PROVIDER_NAME, OptionalInt.of(503), true, sentinel);

            assertThat(ex.getCause()).isSameAs(sentinel);
        }

        @Test
        @DisplayName("wrapping preserves the cause's exact runtime class and message")
        void preservesCauseClassAndMessage() {
            var sentinel = new IOException("sentinel-cause");
            var ex = new ProviderException(ERROR_CODE, MESSAGE, PROVIDER_NAME, OptionalInt.of(503), true, sentinel);

            assertThat(ex.getCause()).isExactlyInstanceOf(IOException.class).hasMessage("sentinel-cause");
        }
    }

    @Nested
    @DisplayName("type")
    class Type {

        @Test
        @DisplayName("ProviderException extends Exception directly (it is CHECKED, not a RuntimeException)")
        void isCheckedException() {
            assertThat(ProviderException.class.getSuperclass()).isEqualTo(Exception.class);
            assertThat(RuntimeException.class.isAssignableFrom(ProviderException.class))
                    .as("ProviderException must NOT be a RuntimeException")
                    .isFalse();
        }
    }
}
