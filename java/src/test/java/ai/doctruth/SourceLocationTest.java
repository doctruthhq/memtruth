package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link SourceLocation}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code pageStart >= 1} — pages are 1-indexed (PDF convention).
 *   <li>{@code pageEnd >= pageStart} — a span cannot end before it starts.
 *   <li>{@code lineStart >= 1} — line numbers are 1-indexed within a page.
 *   <li>{@code lineEnd >= 1} — same reason.
 *   <li>{@code charOffset >= 0} — byte/char offset into the source page text.
 *   <li>If {@code pageStart == pageEnd}, then {@code lineEnd >= lineStart} (intra-page ordering).
 *       Cross-page spans intentionally allow {@code lineEnd < lineStart} because line numbers
 *       are per-page, not document-absolute.
 * </ul>
 */
class SourceLocationTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a single-page, single-line citation")
        void singlePageSingleLine() {
            var loc = new SourceLocation(1, 1, 3, 3, 0);

            assertThat(loc.pageStart()).isEqualTo(1);
            assertThat(loc.pageEnd()).isEqualTo(1);
            assertThat(loc.lineStart()).isEqualTo(3);
            assertThat(loc.lineEnd()).isEqualTo(3);
            assertThat(loc.charOffset()).isZero();
        }

        @Test
        @DisplayName("accepts a multi-line span on the same page")
        void multiLineSamePage() {
            var loc = new SourceLocation(2, 2, 5, 12, 480);

            assertThat(loc.pageStart()).isEqualTo(2);
            assertThat(loc.lineEnd()).isEqualTo(12);
        }

        @Test
        @DisplayName("accepts a cross-page span where lineEnd < lineStart (lines reset per page)")
        void crossPageLineNumbersReset() {
            // page 1 line 50 → page 2 line 3 — perfectly valid, lines are per-page
            var loc = new SourceLocation(1, 2, 50, 3, 12_500);

            assertThat(loc.pageStart()).isEqualTo(1);
            assertThat(loc.pageEnd()).isEqualTo(2);
            assertThat(loc.lineStart()).isEqualTo(50);
            assertThat(loc.lineEnd()).isEqualTo(3);
        }

        @Test
        @DisplayName("two locations with equal fields are equal (record semantics)")
        void recordEquality() {
            var a = new SourceLocation(1, 1, 1, 1, 0);
            var b = new SourceLocation(1, 1, 1, 1, 0);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects pageStart < 1")
        void pageStartZero() {
            assertThatThrownBy(() -> new SourceLocation(0, 1, 1, 1, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pageStart");
        }

        @Test
        @DisplayName("rejects pageStart < 1 (negative)")
        void pageStartNegative() {
            assertThatThrownBy(() -> new SourceLocation(-1, 1, 1, 1, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pageStart");
        }

        @Test
        @DisplayName("rejects pageEnd < pageStart")
        void pageEndBeforePageStart() {
            assertThatThrownBy(() -> new SourceLocation(5, 4, 1, 1, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pageEnd");
        }

        @Test
        @DisplayName("rejects lineStart < 1")
        void lineStartZero() {
            assertThatThrownBy(() -> new SourceLocation(1, 1, 0, 1, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lineStart");
        }

        @Test
        @DisplayName("rejects lineEnd < 1")
        void lineEndZero() {
            assertThatThrownBy(() -> new SourceLocation(1, 1, 1, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lineEnd");
        }

        @Test
        @DisplayName("rejects negative charOffset")
        void charOffsetNegative() {
            assertThatThrownBy(() -> new SourceLocation(1, 1, 1, 1, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("charOffset");
        }

        @Test
        @DisplayName("rejects intra-page lineEnd < lineStart")
        void intraPageLineEndBeforeLineStart() {
            assertThatThrownBy(() -> new SourceLocation(3, 3, 10, 5, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lineEnd");
        }
    }
}
