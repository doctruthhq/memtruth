package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Local smoke for the current PDFBox baseline feeding the v1 trust contract. */
class TrustDocumentLocalSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("PDFBox baseline can produce audit-gated TrustDocument outputs")
    void pdfBaselineToTrustDocumentOutputs() throws Exception {
        var pdf = writePdf("Candidate has Java and OCR experience.");
        var parsed = PdfDocumentParser.parse(pdf);
        var parserRun = new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of());

        var trust = TrustDocument.fromParsed(parsed, "sha256:smoke", parserRun).withEvaluatedAuditGrade();

        assertThat(trust.auditGradeStatus()).isEqualTo(AuditGradeStatus.AUDIT_GRADE);
        assertThat(trust.body().units()).isNotEmpty();
        assertThat(trust.toJsonFull()).contains("\"parserRun\"");
        assertThat(trust.toJsonEvidence()).contains("span-0001");
        assertThat(trust.toMarkdownClean()).contains("Candidate has Java and OCR experience.");
        assertThat(trust.toCompactLlm()).contains("Candidate has Java and OCR experience.");
    }

    private Path writePdf(String text) throws Exception {
        var path = tempDir.resolve("smoke.pdf");
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
}
