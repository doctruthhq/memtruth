package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ExtractionException}.
 *
 * <p>Per CONTRIBUTING.md "Engineering principles" §2 (Auditable + debuggable + loggable everywhere):
 * every public exception carries a stable string {@code errorCode} plus structured context
 * (here: the {@code retries} count consumed before the failure was raised). Per CONTRIBUTING.md
 * "Code style + conventions" — Error handling: this is a CHECKED exception extending
 * {@link Exception} directly (NOT {@link RuntimeException}).
 *
 * <p>Invariants this exception must enforce:
 *
 * <ul>
 *   <li>{@code errorCode} is non-null and non-blank.
 *   <li>{@code message} is non-null.
 *   <li>{@code retries >= 0} (zero is valid — first-attempt failure).
 *   <li>{@code cause} is optional; the cause-less constructor leaves {@link Throwable#getCause()}
 *       returning {@code null}.
 * </ul>
 */
class ExtractionExceptionTest {

    private static final String ERROR_CODE = "EXTRACTION_VALIDATION_FAILED";
    private static final String MESSAGE = "no citation produced for field 'amount'";

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("4-arg constructor (no cause): all four accessors round-trip and getCause() is null")
        void fourArgConstructorRoundTrip() {
            var ex = new ExtractionException(ERROR_CODE, MESSAGE, 2);

            assertThat(ex.getMessage()).isEqualTo(MESSAGE);
            assertThat(ex.errorCode()).isEqualTo(ERROR_CODE);
            assertThat(ex.retries()).isEqualTo(2);
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName(
                "5-arg constructor (with cause): all accessors round-trip and getCause() returns the supplied throwable")
        void fiveArgConstructorRoundTrip() {
            var sentinel = new IllegalStateException("sentinel");
            var ex = new ExtractionException(ERROR_CODE, MESSAGE, 3, sentinel);

            assertThat(ex.getMessage()).isEqualTo(MESSAGE);
            assertThat(ex.errorCode()).isEqualTo(ERROR_CODE);
            assertThat(ex.retries()).isEqualTo(3);
            assertThat(ex.getCause()).isSameAs(sentinel);
        }

        @Test
        @DisplayName("retries == 0 is valid (first-attempt failure)")
        void retriesZeroIsValid() {
            var ex = new ExtractionException(ERROR_CODE, MESSAGE, 0);

            assertThat(ex.retries()).isZero();
        }

        @Test
        @DisplayName("two exceptions with the same fields are NOT .equals() (Java exceptions don't override equals)")
        void exceptionEqualityIsIdentity() {
            var a = new ExtractionException(ERROR_CODE, MESSAGE, 0);
            var b = new ExtractionException(ERROR_CODE, MESSAGE, 0);

            assertThat(a).isNotEqualTo(b);
            assertThat(a).isEqualTo(a);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null errorCode with NullPointerException")
        void nullErrorCode() {
            assertThatThrownBy(() -> new ExtractionException(null, MESSAGE, 0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("errorCode");
        }

        @Test
        @DisplayName("rejects empty errorCode with IllegalArgumentException")
        void emptyErrorCode() {
            assertThatThrownBy(() -> new ExtractionException("", MESSAGE, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("errorCode");
        }

        @Test
        @DisplayName("rejects whitespace-only errorCode with IllegalArgumentException")
        void blankErrorCode() {
            assertThatThrownBy(() -> new ExtractionException("   ", MESSAGE, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("errorCode");
        }

        @Test
        @DisplayName("rejects newline-only errorCode with IllegalArgumentException")
        void newlineOnlyErrorCode() {
            assertThatThrownBy(() -> new ExtractionException("\n", MESSAGE, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("errorCode");
        }

        @Test
        @DisplayName("rejects tab-only errorCode with IllegalArgumentException")
        void tabOnlyErrorCode() {
            assertThatThrownBy(() -> new ExtractionException("\t", MESSAGE, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("errorCode");
        }

        @Test
        @DisplayName("rejects null message with NullPointerException")
        void nullMessage() {
            assertThatThrownBy(() -> new ExtractionException(ERROR_CODE, null, 0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("message");
        }

        @Test
        @DisplayName("rejects negative retries with IllegalArgumentException")
        void negativeRetries() {
            assertThatThrownBy(() -> new ExtractionException(ERROR_CODE, MESSAGE, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retries");
        }

        @Test
        @DisplayName("rejects null errorCode in 5-arg ctor with NullPointerException")
        void nullErrorCodeWithCause() {
            var sentinel = new IllegalStateException("sentinel");
            assertThatThrownBy(() -> new ExtractionException(null, MESSAGE, 0, sentinel))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("errorCode");
        }

        @Test
        @DisplayName("rejects negative retries in 5-arg ctor with IllegalArgumentException")
        void negativeRetriesWithCause() {
            var sentinel = new IllegalStateException("sentinel");
            assertThatThrownBy(() -> new ExtractionException(ERROR_CODE, MESSAGE, -2, sentinel))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retries");
        }
    }

    @Nested
    @DisplayName("cause")
    class Cause {

        @Test
        @DisplayName("getCause() returns the originally-wrapped Throwable (sentinel identity preserved)")
        void preservesCauseIdentity() {
            var sentinel = new IOException("sentinel-cause");
            var ex = new ExtractionException(ERROR_CODE, MESSAGE, 1, sentinel);

            assertThat(ex.getCause()).isSameAs(sentinel);
        }

        @Test
        @DisplayName("wrapping preserves the cause's exact runtime class and message")
        void preservesCauseClassAndMessage() {
            var sentinel = new IOException("sentinel-cause");
            var ex = new ExtractionException(ERROR_CODE, MESSAGE, 1, sentinel);

            assertThat(ex.getCause()).isExactlyInstanceOf(IOException.class).hasMessage("sentinel-cause");
        }
    }

    @Nested
    @DisplayName("type")
    class Type {

        @Test
        @DisplayName("ExtractionException extends Exception directly (it is CHECKED, not a RuntimeException)")
        void isCheckedException() {
            assertThat(ExtractionException.class.getSuperclass()).isEqualTo(Exception.class);
            assertThat(RuntimeException.class.isAssignableFrom(ExtractionException.class))
                    .as("ExtractionException must NOT be a RuntimeException")
                    .isFalse();
        }

        @Test
        @DisplayName(
                "a method declaring throws ExtractionException reflects it in its throws clause (compile-time checked)")
        void declaresCheckedThrows() throws NoSuchMethodException {
            var declaring = ExtractionExceptionTest.class.getDeclaredMethod("throwsExtraction");

            assertThat(declaring.getExceptionTypes()).contains(ExtractionException.class);
        }

        @Test
        @DisplayName("ExtractionException is catchable as Exception (checked-exception contract)")
        void catchableAsException() {
            var caught = false;
            try {
                throw new ExtractionException(ERROR_CODE, MESSAGE, 0);
            } catch (Exception e) {
                caught = true;
                assertThat(e).isInstanceOf(ExtractionException.class);
            }
            assertThat(caught).isTrue();
        }
    }

    @SuppressWarnings("unused")
    private void throwsExtraction() throws ExtractionException {
        throw new ExtractionException(ERROR_CODE, MESSAGE, 0);
    }
}
