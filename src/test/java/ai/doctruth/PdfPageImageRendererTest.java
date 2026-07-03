package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for rendered page image artifacts. */
class PdfPageImageRendererTest {

    @TempDir
    Path tempDir;

    @Test
    void writePngsExportsDeterministicPageImagesAndMetadata() throws Exception {
        Path pdf = writePdf("First page image.", "Second page image.");
        Path outDir = tempDir.resolve("pages");

        var pages = PdfPageImageRenderer.writePngs(pdf, outDir);

        assertThat(pages).hasSize(2);
        assertThat(pages).extracting(TrustPage::pageNumber).containsExactly(1, 2);
        assertThat(pages.getFirst().width()).isEqualTo(612.0);
        assertThat(pages.getFirst().height()).isEqualTo(792.0);
        assertPageImage(outDir.resolve("page-0001.png"), pages.getFirst().imageHash());
        assertPageImage(outDir.resolve("page-0002.png"), pages.get(1).imageHash());
    }

    @Test
    void writePngsRejectsNullInputs() {
        var outDir = tempDir.resolve("pages");

        assertThatNullPointerException()
                .isThrownBy(() -> PdfPageImageRenderer.writePngs(null, outDir))
                .withMessageContaining("pdfPath");
        assertThatNullPointerException()
                .isThrownBy(() -> PdfPageImageRenderer.writePngs(tempDir.resolve("missing.pdf"), null))
                .withMessageContaining("outputDir");
    }

    @Test
    void writePngsMapsInvalidPdfToParseException() throws Exception {
        Path invalid = tempDir.resolve("not-a.pdf");
        Files.writeString(invalid, "not a pdf");

        assertThatThrownBy(() -> PdfPageImageRenderer.writePngs(invalid, tempDir.resolve("out")))
                .isInstanceOf(ParseException.class)
                .extracting("errorCode")
                .isEqualTo("PDF_PAGE_IMAGE_RENDER_FAILED");
    }

    private Path writePdf(String... texts) throws Exception {
        Path path = tempDir.resolve("page-images.pdf");
        try (var pdf = new PDDocument()) {
            for (var text : texts) {
                var page = new PDPage();
                pdf.addPage(page);
                try (var stream = new PDPageContentStream(pdf, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(72, 720);
                    stream.showText(text);
                    stream.endText();
                }
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private static void assertPageImage(Path image, String expectedHash) throws Exception {
        assertThat(Files.exists(image)).isTrue();
        byte[] bytes = Files.readAllBytes(image);
        assertThat(bytes).startsWith(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
        assertThat("sha256:"
                        + HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo(expectedHash);
    }
}
