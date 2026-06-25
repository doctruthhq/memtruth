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
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfTextRenderingNormalizationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("rendered text lines trim and compress repeated spaces")
    void renderedTextLinesNormalizeWhitespace() throws Exception {
        var pdfPath = writePositionedPdf("  Invoice   total    due  ");

        var text = PdfTextPositionMetrics.renderWithInferredSpaces(captureFirstPagePositions(pdfPath));

        assertThat(text).isEqualTo("Invoice total due");
    }

    @Test
    @DisplayName("generated PDF text layer output trims and compresses repeated spaces")
    void generatedPdfTextLayerOutputNormalizesWhitespace() throws Exception {
        var pdfPath = writePositionedPdf("  Invoice   total    due  ");

        var doc = PdfDocumentParser.parse(pdfPath);
        var texts = doc.sections().stream().map(section -> ((TextSection) section).text()).toList();

        assertThat(texts).containsExactly("Invoice total due");
    }

    private Path writePositionedPdf(String text) throws IOException {
        var path = tempDir.resolve("doc-" + System.nanoTime() + ".pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f);
                cs.newLineAtOffset(50f, 720f);
                cs.showText(text);
                cs.endText();
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private static List<TextPosition> captureFirstPagePositions(Path path) throws IOException {
        try (var pdf = Loader.loadPDF(path.toFile())) {
            return PdfPageBlockExtractor.capturePageTextPositions(pdf, 1);
        }
    }
}
