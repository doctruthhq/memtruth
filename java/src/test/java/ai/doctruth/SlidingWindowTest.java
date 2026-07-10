package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link SlidingWindow}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code windowChars >= 1}. Zero / negative window sizes are nonsensical and rejected
 *       with IAE.
 *   <li>{@code overlapChars >= 0}. Zero overlap is allowed (windows are then disjoint);
 *       negative overlap is rejected with IAE.
 *   <li>{@code overlapChars < windowChars}. Strict inequality: equal would mean every window
 *       repeats the previous one entirely (infinite loop on assembly); greater is even worse.
 * </ul>
 */
class SlidingWindowTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a typical (windowChars=4000, overlapChars=500) configuration")
        void typicalConfig() {
            var strategy = new SlidingWindow(4_000, 500);

            assertThat(strategy.windowChars()).isEqualTo(4_000);
            assertThat(strategy.overlapChars()).isEqualTo(500);
        }

        @Test
        @DisplayName("accepts overlapChars = 0 (disjoint windows)")
        void zeroOverlapAllowed() {
            var strategy = new SlidingWindow(1_000, 0);

            assertThat(strategy.overlapChars()).isZero();
        }

        @Test
        @DisplayName("accepts the boundary case overlapChars = windowChars - 1 (maximum allowed overlap)")
        void overlapJustBelowWindow() {
            var strategy = new SlidingWindow(1_000, 999);

            assertThat(strategy.windowChars()).isEqualTo(1_000);
            assertThat(strategy.overlapChars()).isEqualTo(999);
        }

        @Test
        @DisplayName("two SlidingWindows with equal fields are equal (record semantics)")
        void recordEquality() {
            var a = new SlidingWindow(2_000, 200);
            var b = new SlidingWindow(2_000, 200);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects windowChars = 0 with IllegalArgumentException")
        void windowCharsZero() {
            assertThatThrownBy(() -> new SlidingWindow(0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("windowChars");
        }

        @Test
        @DisplayName("rejects windowChars = -1 with IllegalArgumentException")
        void windowCharsNegative() {
            assertThatThrownBy(() -> new SlidingWindow(-1, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("windowChars");
        }

        @Test
        @DisplayName("rejects overlapChars = -1 with IllegalArgumentException")
        void overlapCharsNegative() {
            assertThatThrownBy(() -> new SlidingWindow(1_000, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("overlapChars");
        }

        @Test
        @DisplayName("rejects overlapChars equal to windowChars (would cause infinite loop on assembly)")
        void overlapEqualToWindow() {
            assertThatThrownBy(() -> new SlidingWindow(1_000, 1_000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("overlapChars");
        }

        @Test
        @DisplayName("rejects overlapChars greater than windowChars with IllegalArgumentException")
        void overlapGreaterThanWindow() {
            assertThatThrownBy(() -> new SlidingWindow(1_000, 1_001))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("overlapChars");
        }
    }
}
