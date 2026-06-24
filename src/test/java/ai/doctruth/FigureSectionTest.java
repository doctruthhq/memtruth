package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link FigureSection}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code caption} is non-null. The empty string IS allowed — figures without captions
 *       are common in real PDFs and we model "no caption" as "" rather than {@code null}.
 *   <li>{@code location} is non-null.
 * </ul>
 */
class FigureSectionTest {

    private static final SourceLocation LOC = new SourceLocation(1, 1, 1, 1, 0);
    private static final BoundingBox BOX = new BoundingBox(100, 100, 500, 200);

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a non-empty caption plus a location")
        void nonEmptyCaption() {
            var section = new FigureSection("Figure 1: Quarterly Revenue", LOC);

            assertThat(section.caption()).isEqualTo("Figure 1: Quarterly Revenue");
            assertThat(section.location()).isEqualTo(LOC);
            assertThat(section.boundingBox()).isEmpty();
        }

        @Test
        @DisplayName("accepts an optional caption bounding box")
        void captionBoundingBox() {
            var section = new FigureSection("Figure 1", LOC, Optional.of(BOX));

            assertThat(section.boundingBox()).contains(BOX);
        }

        @Test
        @DisplayName("accepts an empty caption (uncaptioned figures are valid)")
        void emptyCaptionAllowed() {
            var section = new FigureSection("", LOC);

            assertThat(section.caption()).isEmpty();
            assertThat(section.location()).isEqualTo(LOC);
        }

        @Test
        @DisplayName("two FigureSections with equal fields are equal (record semantics)")
        void recordEquality() {
            var a = new FigureSection("Figure 1", LOC);
            var b = new FigureSection("Figure 1", LOC);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("is assignable to ParsedSection (sealed interface acceptance)")
        void isParsedSection() {
            ParsedSection section = new FigureSection("Figure 1", LOC);

            assertThat(section).isInstanceOf(FigureSection.class);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null caption with NullPointerException")
        void nullCaption() {
            assertThatThrownBy(() -> new FigureSection(null, LOC))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("caption");
        }

        @Test
        @DisplayName("rejects null location with NullPointerException")
        void nullLocation() {
            assertThatThrownBy(() -> new FigureSection("Figure 1", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("location");
        }

        @Test
        @DisplayName("rejects null boundingBox optional with NullPointerException")
        void nullBoundingBox() {
            assertThatThrownBy(() -> new FigureSection("Figure 1", LOC, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("boundingBox");
        }
    }
}
