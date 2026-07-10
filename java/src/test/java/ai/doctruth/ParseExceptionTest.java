package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ParseException}.
 *
 * <p>Per CONTRIBUTING.md "Engineering principles" §2 (Auditable + debuggable + loggable everywhere):
 * a parse failure must surface enough structured context for the caller to localise the problem
 * — the source name (e.g. PDF filename) and, when the parser knows it, the failing page. Per
 * CONTRIBUTING.md "Code style + conventions" — Error handling: this is a CHECKED exception extending
 * {@link Exception} directly (NOT {@link RuntimeException}).
 *
 * <p>Invariants this exception must enforce:
 *
 * <ul>
 *   <li>{@code errorCode} is non-null and non-blank.
 *   <li>{@code message} is non-null.
 *   <li>{@code sourceName} is non-null and non-blank.
 *   <li>{@code pageNumber} is an {@link OptionalInt}; the {@code OptionalInt} itself is non-null.
 *       Callers pass {@link OptionalInt#empty()} when the page is unknown — never {@code null}.
 *   <li>{@code cause} is optional.
 * </ul>
 *
 * <p>Per CONTRIBUTING.md "Engineering principles" §4 (Build, don't synthesize): we use the JDK-idiomatic
 * primitive {@link OptionalInt} rather than {@code Optional<Integer>} to avoid Integer boxing.
 */
class ParseExceptionTest {

    private static final String ERROR_CODE = "PARSE_PDF_ENCRYPTED";
    private static final String MESSAGE = "PDF stream is password-protected and no password supplied";
    private static final String SOURCE_NAME = "tender-2026-q1.pdf";

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("4-arg constructor (no cause): all accessors round-trip and getCause() is null")
        void fourArgConstructorRoundTrip() {
            var ex = new ParseException(ERROR_CODE, MESSAGE, SOURCE_NAME, OptionalInt.of(7));

            assertThat(ex.getMessage()).isEqualTo(MESSAGE);
            assertThat(ex.errorCode()).isEqualTo(ERROR_CODE);
            assertThat(ex.sourceName()).isEqualTo(SOURCE_NAME);
            assertThat(ex.pageNumber()).hasValue(7);
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName(
                "5-arg constructor (with cause): all accessors round-trip and getCause() returns the supplied throwable")
        void fiveArgConstructorRoundTrip() {
            var sentinel = new IllegalStateException("sentinel");
            var ex = new ParseException(ERROR_CODE, MESSAGE, SOURCE_NAME, OptionalInt.of(7), sentinel);

            assertThat(ex.getMessage()).isEqualTo(MESSAGE);
            assertThat(ex.errorCode()).isEqualTo(ERROR_CODE);
            assertThat(ex.sourceName()).isEqualTo(SOURCE_NAME);
            assertThat(ex.pageNumber()).hasValue(7);
            assertThat(ex.getCause()).isSameAs(sentinel);
        }

        @Test
        @DisplayName("OptionalInt.empty() page number round-trips (page unknown to parser)")
        void emptyPageNumberRoundTrip() {
            var ex = new ParseException(ERROR_CODE, MESSAGE, SOURCE_NAME, OptionalInt.empty());

            assertThat(ex.pageNumber()).isEmpty();
        }

        @Test
        @DisplayName("OptionalInt.of(7) page number round-trips")
        void presentPageNumberRoundTrip() {
            var ex = new ParseException(ERROR_CODE, MESSAGE, SOURCE_NAME, OptionalInt.of(7));

            assertThat(ex.pageNumber()).hasValue(7);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null errorCode with NullPointerException")
        void nullErrorCode() {
            assertThatThrownBy(() -> new ParseException(null, MESSAGE, SOURCE_NAME, OptionalInt.empty()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("errorCode");
        }

        @Test
        @DisplayName("rejects empty errorCode with IllegalArgumentException")
        void emptyErrorCode() {
            assertThatThrownBy(() -> new ParseException("", MESSAGE, SOURCE_NAME, OptionalInt.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("errorCode");
        }

        @Test
        @DisplayName("rejects whitespace-only errorCode with IllegalArgumentException")
        void blankErrorCode() {
            assertThatThrownBy(() -> new ParseException("   ", MESSAGE, SOURCE_NAME, OptionalInt.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("errorCode");
        }

        @Test
        @DisplayName("rejects null message with NullPointerException")
        void nullMessage() {
            assertThatThrownBy(() -> new ParseException(ERROR_CODE, null, SOURCE_NAME, OptionalInt.empty()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("message");
        }

        @Test
        @DisplayName("rejects null sourceName with NullPointerException")
        void nullSourceName() {
            assertThatThrownBy(() -> new ParseException(ERROR_CODE, MESSAGE, null, OptionalInt.empty()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sourceName");
        }

        @Test
        @DisplayName("rejects empty sourceName with IllegalArgumentException")
        void emptySourceName() {
            assertThatThrownBy(() -> new ParseException(ERROR_CODE, MESSAGE, "", OptionalInt.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceName");
        }

        @Test
        @DisplayName("rejects whitespace-only sourceName with IllegalArgumentException")
        void blankSourceName() {
            assertThatThrownBy(() -> new ParseException(ERROR_CODE, MESSAGE, "   ", OptionalInt.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceName");
        }

        @Test
        @DisplayName(
                "rejects null pageNumber Optional with NullPointerException (callers must use OptionalInt.empty())")
        void nullPageNumberOptional() {
            assertThatThrownBy(() -> new ParseException(ERROR_CODE, MESSAGE, SOURCE_NAME, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("pageNumber");
        }
    }

    @Nested
    @DisplayName("cause")
    class Cause {

        @Test
        @DisplayName("5-arg ctor preserves cause via getCause() (sentinel identity preserved)")
        void preservesCauseIdentity() {
            var sentinel = new IOException("sentinel-cause");
            var ex = new ParseException(ERROR_CODE, MESSAGE, SOURCE_NAME, OptionalInt.of(3), sentinel);

            assertThat(ex.getCause()).isSameAs(sentinel);
        }

        @Test
        @DisplayName("wrapping preserves the cause's exact runtime class and message")
        void preservesCauseClassAndMessage() {
            var sentinel = new IOException("sentinel-cause");
            var ex = new ParseException(ERROR_CODE, MESSAGE, SOURCE_NAME, OptionalInt.of(3), sentinel);

            assertThat(ex.getCause()).isExactlyInstanceOf(IOException.class).hasMessage("sentinel-cause");
        }
    }

    @Nested
    @DisplayName("type")
    class Type {

        @Test
        @DisplayName("ParseException extends Exception directly (it is CHECKED, not a RuntimeException)")
        void isCheckedException() {
            assertThat(ParseException.class.getSuperclass()).isEqualTo(Exception.class);
            assertThat(RuntimeException.class.isAssignableFrom(ParseException.class))
                    .as("ParseException must NOT be a RuntimeException")
                    .isFalse();
        }
    }
}
