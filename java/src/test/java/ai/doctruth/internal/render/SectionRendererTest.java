package ai.doctruth.internal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import ai.doctruth.FigureSection;
import ai.doctruth.SourceLocation;
import ai.doctruth.TableSection;
import ai.doctruth.TextSection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link SectionRenderer}.
 *
 * <p>{@code SectionRenderer} is the single source of "render one parsed section to a flat
 * string" logic shared between {@code ExtractionBuilder.renderUserPrompt} and
 * {@code PriorityTruncate.assemble}. Per CONTRIBUTING.md "Engineering principles" §1
 * (decoupled by default, single source of truth), this rendering MUST live in exactly one
 * place; if a future caller needs different behaviour they should extend with a sibling
 * renderer rather than duplicating.
 *
 * <p>Rendering contract:
 *
 * <ul>
 *   <li>{@link TextSection} → its raw {@code text()} content (no decoration).
 *   <li>{@link TableSection} → each row rendered as cells joined by {@code " | "}, rows
 *       separated by {@code "\n"} (matches the existing prompt convention so the LLM can
 *       parse simple pipe-separated tables).
 *   <li>{@link FigureSection} → {@code "[Figure: <caption>]"} marker so the LLM is told a
 *       figure existed without seeing the bytes (figures aren't carried in this layer).
 * </ul>
 */
class SectionRendererTest {

    private static final SourceLocation ANYWHERE = new SourceLocation(1, 1, 1, 1, 0);

    @Nested
    @DisplayName("text section")
    class TextSectionRendering {

        @Test
        @DisplayName("returns the raw text() with no surrounding decoration")
        void textRendersAsRawString() {
            var section = new TextSection("hello world", ANYWHERE);

            assertThat(SectionRenderer.render(section)).isEqualTo("hello world");
        }

        @Test
        @DisplayName("preserves an empty text() exactly")
        void emptyTextIsEmpty() {
            var section = new TextSection("", ANYWHERE);

            assertThat(SectionRenderer.render(section)).isEmpty();
        }
    }

    @Nested
    @DisplayName("table section")
    class TableSectionRendering {

        @Test
        @DisplayName("renders rows as ' | '-joined cells separated by newlines")
        void typicalTwoRowTable() {
            var table = new TableSection(List.of(List.of("Name", "Age"), List.of("Alex", "30")), ANYWHERE);

            assertThat(SectionRenderer.render(table)).isEqualTo("Name | Age\nAlex | 30");
        }

        @Test
        @DisplayName("renders a single-row table without a trailing newline")
        void singleRowNoTrailingNewline() {
            var table = new TableSection(List.of(List.of("only", "row")), ANYWHERE);

            assertThat(SectionRenderer.render(table)).isEqualTo("only | row");
        }

        @Test
        @DisplayName("an empty table renders as an empty string")
        void emptyTableIsEmpty() {
            var table = new TableSection(List.of(), ANYWHERE);

            assertThat(SectionRenderer.render(table)).isEmpty();
        }
    }

    @Nested
    @DisplayName("figure section")
    class FigureSectionRendering {

        @Test
        @DisplayName("renders as '[Figure: <caption>]'")
        void figureWithCaption() {
            var figure = new FigureSection("Building cross-section", ANYWHERE);

            assertThat(SectionRenderer.render(figure)).isEqualTo("[Figure: Building cross-section]");
        }

        @Test
        @DisplayName("renders an empty caption as '[Figure: ]'")
        void figureWithEmptyCaption() {
            var figure = new FigureSection("", ANYWHERE);

            assertThat(SectionRenderer.render(figure)).isEqualTo("[Figure: ]");
        }
    }
}
