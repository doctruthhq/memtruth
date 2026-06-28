package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for parser backends that feed the v1 trust document runtime. */
class ParserBackendContractTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("PDFBox backend advertises local PDF baseline capabilities")
    void pdfBoxBackendCapabilities() {
        ParserBackend backend = new PdfBoxParserBackend();

        var capabilities = backend.capabilities();
        var health = backend.doctor();

        assertThat(capabilities.backend()).isEqualTo("pdfbox");
        assertThat(capabilities.supportsPdf()).isTrue();
        assertThat(capabilities.supportsModels()).isFalse();
        assertThat(capabilities.networkRequired()).isFalse();
        assertThat(capabilities.outputProfiles()).contains("json_full", "markdown_clean", "plain_text", "compact_llm");
        assertThat(health.available()).isTrue();
        assertThat(health.warnings()).isEmpty();
    }

    @Test
    @DisplayName("PDFBox backend parses offline without model downloads")
    void pdfBoxBackendParsesOfflineWithoutModelDownloads() throws Exception {
        ParserBackend backend = new PdfBoxParserBackend();
        var pdf = writePdf("Offline parser backend smoke.");
        var parserRun = new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of());
        var request = new ParserRequest(pdf, "sha256:offline", parserRun, true, false);

        var trust = backend.parse(request).withEvaluatedAuditGrade();

        assertThat(trust.auditGradeStatus()).isEqualTo(AuditGradeStatus.AUDIT_GRADE);
        assertThat(trust.parserRun().backend()).isEqualTo("pdfbox");
        assertThat(trust.toMarkdownClean()).contains("Offline parser backend smoke.");
        assertThat(trust.toCompactLlm()).contains("span-0001");
    }

    @Test
    @DisplayName("PDFBox backend records rendered page dimensions and image hash")
    void pdfBoxBackendRecordsRenderedPageImageHash() throws Exception {
        ParserBackend backend = new PdfBoxParserBackend();
        var pdf = writePdf("Rendered page image hash smoke.");
        var parserRun = new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of());
        var request = new ParserRequest(pdf, "sha256:rendered-page", parserRun, true, false);

        var trust = backend.parse(request);

        assertThat(trust.body().pages()).hasSize(1);
        var page = trust.body().pages().getFirst();
        assertThat(page.width()).isEqualTo(612.0);
        assertThat(page.height()).isEqualTo(792.0);
        assertThat(page.imageHash()).isEqualTo(renderedPageHash(pdf, 0));
    }

    private Path writePdf(String text) throws Exception {
        var path = tempDir.resolve("backend-smoke.pdf");
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private static String renderedPageHash(Path pdf, int pageIndex) throws Exception {
        try (var doc = Loader.loadPDF(pdf.toFile())) {
            var image = new PDFRenderer(doc).renderImageWithDPI(pageIndex, 72);
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        }
    }
}
