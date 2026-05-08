package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link TextSection}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code text} is non-null. The empty string IS allowed — a zero-length text run is a
 *       legitimate parse outcome (e.g. a whitespace-only paragraph that survived layout).
 *   <li>{@code location} is non-null.
 *   <li>{@code kind} is non-null. Use {@link BlockKind#OTHER} for "didn't classify".
 * </ul>
 */
class TextSectionTest {

    private static final SourceLocation LOC = new SourceLocation(1, 1, 1, 1, 0);

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("two-arg constructor accepts text + location and defaults kind to OTHER")
        void nonEmptyTextTwoArg() {
            var section = new TextSection("the quick brown fox", LOC);

            assertThat(section.text()).isEqualTo("the quick brown fox");
            assertThat(section.location()).isEqualTo(LOC);
            assertThat(section.kind()).isEqualTo(BlockKind.OTHER);
        }

        @Test
        @DisplayName("three-arg constructor retains the supplied kind verbatim")
        void threeArgRetainsKind() {
            var section = new TextSection("Section 1 — Indemnities", LOC, BlockKind.HEADING);

            assertThat(section.text()).isEqualTo("Section 1 — Indemnities");
            assertThat(section.location()).isEqualTo(LOC);
            assertThat(section.kind()).isEqualTo(BlockKind.HEADING);
        }

        @Test
        @DisplayName("accepts an empty string for text (empty paragraphs are valid parse outputs)")
        void emptyTextAllowed() {
            var section = new TextSection("", LOC, BlockKind.BODY);

            assertThat(section.text()).isEmpty();
            assertThat(section.location()).isEqualTo(LOC);
            assertThat(section.kind()).isEqualTo(BlockKind.BODY);
        }

        @Test
        @DisplayName("two TextSections with equal fields (incl. kind) are equal (record semantics)")
        void recordEquality() {
            var a = new TextSection("hello", LOC, BlockKind.LIST);
            var b = new TextSection("hello", LOC, BlockKind.LIST);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("two TextSections that differ only in kind are NOT equal")
        void kindParticipatesInEquality() {
            var heading = new TextSection("hello", LOC, BlockKind.HEADING);
            var body = new TextSection("hello", LOC, BlockKind.BODY);

            assertThat(heading).isNotEqualTo(body);
        }

        @Test
        @DisplayName("is assignable to ParsedSection (sealed interface acceptance)")
        void isParsedSection() {
            ParsedSection section = new TextSection("hi", LOC, BlockKind.OTHER);

            assertThat(section).isInstanceOf(TextSection.class);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null text with NullPointerException")
        void nullText() {
            assertThatThrownBy(() -> new TextSection(null, LOC, BlockKind.OTHER))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("text");
        }

        @Test
        @DisplayName("rejects null location with NullPointerException")
        void nullLocation() {
            assertThatThrownBy(() -> new TextSection("hello", null, BlockKind.OTHER))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("location");
        }

        @Test
        @DisplayName("rejects null kind with NullPointerException")
        void nullKind() {
            assertThatThrownBy(() -> new TextSection("hello", LOC, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("kind");
        }

        @Test
        @DisplayName("two-arg convenience constructor still rejects null text")
        void twoArgNullText() {
            assertThatThrownBy(() -> new TextSection(null, LOC))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("text");
        }

        @Test
        @DisplayName("two-arg convenience constructor still rejects null location")
        void twoArgNullLocation() {
            assertThatThrownBy(() -> new TextSection("hello", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("location");
        }
    }
}
