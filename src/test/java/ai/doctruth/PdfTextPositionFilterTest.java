package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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

    private static PdfTextPositionFilter.TextBox position(
            String text, double x, double y, double width, double height) {
        return new PdfTextPositionFilter.TextBox(text, x, y, width, height);
    }
}
