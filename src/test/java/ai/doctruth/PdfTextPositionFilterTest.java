package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

class PdfTextPositionFilterTest {

    @Test
    void filtersTinyOffPageAndControlOnlyText() {
        var keep = position("Visible", 10, 20, 30, 12);
        var tiny = position("tiny", 10, 20, 30, 1);
        var offPage = position("off", 700, 20, 30, 12);
        var control = position("\u0000", 10, 40, 30, 12);

        var filtered = PdfTextPositionFilter.filterBoxes(List.of(keep, tiny, offPage, control), 600, 800);

        assertThat(filtered).containsExactly(keep);
    }

    @Test
    void removesSameTextWithLargeOverlappingBox() {
        var first = position("Total", 100, 200, 40, 12);
        var duplicate = position("Total", 101, 201, 40, 12);
        var distinct = position("Total", 200, 200, 40, 12);

        var filtered = PdfTextPositionFilter.filterBoxes(List.of(first, duplicate, distinct), 600, 800);

        assertThat(filtered).containsExactly(first, distinct);
    }

    @Test
    void removesContainedSameBaselineFragmentsWhenLargerPhraseOverlaps() {
        var phrase = position("Invoice total due", 100, 200, 120, 12);
        var invoice = position("Invoice", 100, 200, 42, 12);
        var total = position("total", 148, 200, 28, 12);
        var due = position("due", 182, 200, 20, 12);

        var filtered = PdfTextPositionFilter.filterBoxes(List.of(phrase, invoice, total, due), 600, 800);

        assertThat(filtered).containsExactly(phrase);
    }

    @Test
    void keepsContainedFragmentsInSeparateRowsColumnsOrNonOverlappingBaselines() {
        var phrase = position("Invoice total due", 100, 200, 120, 12);
        var nextRow = position("Invoice", 100, 225, 42, 12);
        var separateColumn = position("total", 340, 200, 28, 12);
        var nearButSeparateBaseline = position("due", 182, 216, 20, 12);

        var filtered = PdfTextPositionFilter.filterBoxes(
                List.of(phrase, nextRow, separateColumn, nearButSeparateBaseline), 600, 800);

        assertThat(filtered).containsExactly(phrase, nextRow, separateColumn, nearButSeparateBaseline);
    }

    @Test
    void keepsDistinctTextAndSameTextWithClearlyDistinctGeometry() {
        var phrase = position("Invoice total due", 100, 200, 120, 12);
        var distinctText = position("Invoice number", 100, 200, 95, 12);
        var repeatedElsewhere = position("Invoice total due", 100, 250, 120, 12);

        var filtered = PdfTextPositionFilter.filterBoxes(List.of(phrase, distinctText, repeatedElsewhere), 600, 800);

        assertThat(filtered).containsExactly(phrase, distinctText, repeatedElsewhere);
    }

    @Test
    void keepsPartiallyVisiblePageEdgeText() {
        var edge = position("edge", -2, 20, 20, 12);

        var filtered = PdfTextPositionFilter.filterBoxes(List.of(edge), 600, 800);

        assertThat(filtered).containsExactly(edge);
    }

    @Test
    void filtersBackgroundSizedTextBoxes() {
        var keep = position("Visible", 10, 20, 30, 12);
        var wideBackground = position("CONFIDENTIAL", 20, 200, 400, 120);
        var tallBackground = position("DRAFT", 200, 20, 90, 500);

        var filtered = PdfTextPositionFilter.filterBoxes(List.of(keep, wideBackground, tallBackground), 600, 800);

        assertThat(filtered).containsExactly(keep);
    }

    @Test
    void normalizesLeadingTrailingAndConsecutiveInternalSpaces() {
        var box = position("  Invoice   total    due  ", 10, 20, 120, 12);

        var filtered = PdfTextPositionFilter.filterBoxes(List.of(box), 600, 800);

        assertThat(filtered).containsExactly(position("Invoice total due", 10, 20, 120, 12));
    }

    @Test
    void normalizesProductionTextPositionsForDuplicateComparisonOnly() {
        var phrase = textPosition("  Invoice   total    due  ", 100, 200, 120, 12);
        var contained = textPosition("total due", 148, 200, 54, 12);
        var sameText = textPosition("Invoice total due", 101, 201, 120, 12);

        var filtered = PdfTextPositionFilter.filter(List.of(phrase, contained, sameText), 600, 800);

        assertThat(filtered).containsExactly(phrase);
        assertThat(filtered.getFirst().getUnicode()).isEqualTo("  Invoice   total    due  ");
    }

    @Test
    void filtersBoxesBlankAfterNormalization() {
        var whitespace = position(" \t   ", 10, 20, 30, 12);

        var filtered = PdfTextPositionFilter.filterBoxes(List.of(whitespace), 600, 800);

        assertThat(filtered).isEmpty();
    }

    @Test
    void keepsSameNormalizedTextWhenSizeOrGeometryIsDistinct() {
        var first = position(" Total   due ", 100, 200, 40, 12);
        var differentSize = position("Total due", 101, 201, 70, 12);
        var separated = position("Total due", 220, 200, 40, 12);

        var filtered = PdfTextPositionFilter.filterBoxes(List.of(first, differentSize, separated), 600, 800);

        assertThat(filtered)
                .containsExactly(
                        position("Total due", 100, 200, 40, 12),
                        differentSize,
                        separated);
    }

    @Test
    void measuresReplacementCharacterRatioAfterNormalization() {
        var boxes = List.of(position("A \uFFFD", 10, 20, 30, 12), position(" \uFFFD  B ", 40, 20, 30, 12));

        assertThat(PdfTextPositionFilter.replacementCharacterRatio(boxes)).isEqualTo(2.0 / 6.0);
        assertThat(PdfTextPositionFilter.hasHighReplacementCharacterRatio(boxes)).isTrue();
    }

    private static PdfTextPositionFilter.TextBox position(
            String text, double x, double y, double width, double height) {
        return new PdfTextPositionFilter.TextBox(text, x, y, width, height);
    }

    private static TextPosition textPosition(String text, double x, double y, double width, double height) {
        return new TextPosition(
                0,
                600,
                800,
                new Matrix(1, 0, 0, 1, (float) x, (float) y),
                (float) (x + width),
                (float) y,
                (float) height,
                (float) width,
                (float) height,
                text,
                new int[] {text.codePointAt(0)},
                null,
                10,
                10);
    }
}
