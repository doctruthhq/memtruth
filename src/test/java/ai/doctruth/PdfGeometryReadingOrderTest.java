package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfGeometryReadingOrderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("projection cuts keep above-heading two-column text before the full-width heading")
    void projectionCutsKeepAboveHeadingColumnsBeforeFullWidthHeading() throws Exception {
        var pdfPath = writePositionedPdf(List.of(
                run("LEFT ABOVE", 50f, 720f),
                run("RIGHT ABOVE", 320f, 720f),
                run(
                        "FULL WIDTH NEXT SECTION SPANS BOTH COLUMNS AND CONTINUES ACROSS PAGE",
                        50f,
                        650f,
                        12f,
                        Standard14Fonts.FontName.HELVETICA_BOLD),
                run("LEFT BELOW", 50f, 625f),
                run("RIGHT BELOW", 320f, 625f)));

        var text = renderedText(pdfPath);

        assertThat(text).containsSubsequence(
                "LEFT ABOVE",
                "RIGHT ABOVE",
                "FULL WIDTH NEXT SECTION SPANS BOTH COLUMNS",
                "LEFT BELOW",
                "RIGHT BELOW");
    }

    private String renderedText(Path pdfPath) throws IOException {
        try (var pdf = Loader.loadPDF(pdfPath.toFile())) {
            return PdfPageBlockExtractor.detectBlocksOnPage(pdf, 1).stream()
                    .map(PdfTextBlock::text)
                    .reduce("", (left, right) -> left + "\n" + right);
        }
    }

    private Path writePositionedPdf(List<PositionedRun> runs) throws IOException {
        var path = tempDir.resolve("doc-" + System.nanoTime() + ".pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
                for (var run : runs) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(run.fontName()), run.fontSize());
                    cs.newLineAtOffset(run.x(), run.y());
                    cs.showText(run.text());
                    cs.endText();
                }
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private static PositionedRun run(String text, float x, float y) {
        return run(text, x, y, 10f, Standard14Fonts.FontName.HELVETICA);
    }

    private static PositionedRun run(
            String text, float x, float y, float fontSize, Standard14Fonts.FontName fontName) {
        return new PositionedRun(text, x, y, fontSize, fontName);
    }

    private record PositionedRun(String text, float x, float y, float fontSize, Standard14Fonts.FontName fontName) {}
}
