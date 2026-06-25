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

    @Test
    @DisplayName("no-cut fallback keeps dense two-column text column-contiguous")
    void noCutFallbackKeepsDenseTwoColumnTextColumnContiguous() throws Exception {
        var pdfPath = writePositionedPdf(List.of(
                run(
                        "DENSE HEADER BRIDGES BOTH COLUMNS AND SUPPRESSES A CLEAN VERTICAL CUT",
                        50f,
                        720f,
                        10f,
                        Standard14Fonts.FontName.HELVETICA_BOLD),
                run("LEFT COLUMN FIRST DETAIL", 50f, 710f),
                run("RIGHT COLUMN FIRST DETAIL", 320f, 710f),
                run("LEFT COLUMN SECOND DETAIL", 50f, 700f),
                run("RIGHT COLUMN SECOND DETAIL", 320f, 700f)));

        var text = renderedText(pdfPath);

        assertThat(text).containsSubsequence(
                "DENSE HEADER BRIDGES BOTH COLUMNS",
                "LEFT COLUMN FIRST DETAIL",
                "LEFT COLUMN SECOND DETAIL",
                "RIGHT COLUMN FIRST DETAIL",
                "RIGHT COLUMN SECOND DETAIL");
    }

    @Test
    @DisplayName("narrow center outlier does not prevent two-column vertical reading order")
    void narrowCenterOutlierDoesNotPreventTwoColumnVerticalReadingOrder() throws Exception {
        var pdfPath = writePositionedPdf(List.of(
                run("Left alpha detail", 50f, 720f, 8f, Standard14Fonts.FontName.HELVETICA),
                run("Right alpha detail", 124f, 720f, 8f, Standard14Fonts.FontName.HELVETICA),
                run("||||", 111f, 705f, 8f, Standard14Fonts.FontName.HELVETICA),
                run("Left beta detail", 50f, 690f, 8f, Standard14Fonts.FontName.HELVETICA),
                run("Right beta detail", 124f, 690f, 8f, Standard14Fonts.FontName.HELVETICA)));

        var text = renderedText(pdfPath);

        assertThat(text.lines().filter(line -> !line.isBlank()).toList()).containsExactly(
                "Left alpha detail",
                "Left beta detail",
                "||||",
                "Right alpha detail",
                "Right beta detail");
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
