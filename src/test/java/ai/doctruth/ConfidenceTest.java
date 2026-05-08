package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link Confidence}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code score} is a finite number in {@code [0.0, 1.0]} inclusive
 *       (NaN and infinities rejected).
 *   <li>{@code rationale} is non-null. An empty string {@code ""} is allowed —
 *       callers may produce a confidence score without a human-readable rationale.
 * </ul>
 */
class ConfidenceTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a confidence with mid-range score and a rationale")
        void typicalConfidence() {
            var c = new Confidence(0.82, "high lexical overlap with quoted span");

            assertThat(c.score()).isEqualTo(0.82);
            assertThat(c.rationale()).isEqualTo("high lexical overlap with quoted span");
        }

        @Test
        @DisplayName("accepts score boundary 0.0 (zero confidence)")
        void scoreLowerBoundary() {
            var c = new Confidence(0.0, "no match");

            assertThat(c.score()).isZero();
        }

        @Test
        @DisplayName("accepts score boundary 1.0 (full confidence)")
        void scoreUpperBoundary() {
            var c = new Confidence(1.0, "exact match");

            assertThat(c.score()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("accepts empty-string rationale (allowed: scorer may have no rationale)")
        void emptyRationaleIsAllowed() {
            var c = new Confidence(0.5, "");

            assertThat(c.rationale()).isEmpty();
        }

        @Test
        @DisplayName("two confidences with equal fields are equal (record semantics)")
        void recordEquality() {
            var a = new Confidence(0.82, "rationale");
            var b = new Confidence(0.82, "rationale");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null rationale with NullPointerException")
        void nullRationale() {
            assertThatThrownBy(() -> new Confidence(0.5, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("rationale");
        }

        @Test
        @DisplayName("rejects score just below 0.0 with IllegalArgumentException")
        void scoreBelowZero() {
            assertThatThrownBy(() -> new Confidence(-0.0001, "r"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("score");
        }

        @Test
        @DisplayName("rejects score just above 1.0 with IllegalArgumentException")
        void scoreAboveOne() {
            assertThatThrownBy(() -> new Confidence(1.0001, "r"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("score");
        }

        @Test
        @DisplayName("rejects score NaN with IllegalArgumentException")
        void scoreNaN() {
            assertThatThrownBy(() -> new Confidence(Double.NaN, "r"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("score");
        }

        @Test
        @DisplayName("rejects score positive infinity with IllegalArgumentException")
        void scorePositiveInfinity() {
            assertThatThrownBy(() -> new Confidence(Double.POSITIVE_INFINITY, "r"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("score");
        }

        @Test
        @DisplayName("rejects score negative infinity with IllegalArgumentException")
        void scoreNegativeInfinity() {
            assertThatThrownBy(() -> new Confidence(Double.NEGATIVE_INFINITY, "r"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("score");
        }
    }
}
