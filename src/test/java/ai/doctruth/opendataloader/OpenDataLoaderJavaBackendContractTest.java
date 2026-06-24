package ai.doctruth.opendataloader;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import ai.doctruth.ParserPreset;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenDataLoaderJavaBackendContractTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesPdfIntoStructuredOpenDataLoaderResponse() throws Exception {
        var pdf = writePdf("OpenDataLoader Java Core", "Evidence backed parser response.");
        var backend = new OpenDataLoaderJavaBackend();

        var response = backend.parse(new OpenDataLoaderBackendRequest(pdf, ParserPreset.LITE));

        assertThat(response.backend()).isEqualTo("opendataloader-java-core");
        assertThat(response.schemaVersion()).isEqualTo("doctruth.opendataloader.backend.v1");
        assertThat(response.markdown()).contains("OpenDataLoader Java Core");
        assertThat(response.blocks()).isNotEmpty();
        assertThat(response.blocks().getFirst().id()).isNotBlank();
        assertThat(response.blocks().getFirst().kind()).isNotBlank();
        assertThat(response.blocks().getFirst().pageIndex()).isZero();
        assertThat(response.blocks().getFirst().readingOrder()).isGreaterThanOrEqualTo(0);
        assertThat(response.blocks().getFirst().text()).contains("OpenDataLoader Java Core");
        assertThat(response.blocks().getFirst().kind()).isEqualTo("heading");
        assertThat(response.blocks().getFirst().bbox()).isPresent();
        assertThat(response.tables()).isNotNull();
        assertThat(response.headings())
                .extracting(OpenDataLoaderBlock::text)
                .contains("OpenDataLoader Java Core");
        assertThat(response.sourceMap()).isNotEmpty();
        assertThat(response.sourceMap().getFirst().unitId()).isEqualTo(response.blocks().getFirst().sourceUnitId());
        assertThat(response.warnings()).isNotNull();
        assertThat(response.metrics()).containsKey("elapsedMs");
        assertThat(response.trustDocument().parserRun().backend()).isEqualTo("opendataloader-java-core");
    }

    @Test
    void responseCanRoundTripThroughTrustDocumentWithoutLosingSourceRefs() throws Exception {
        var pdf = writePdf("TrustDocument source refs", "The source map must survive adaptation.");
        var response = new OpenDataLoaderJavaBackend()
                .parse(new OpenDataLoaderBackendRequest(pdf, ParserPreset.LITE));

        assertThat(response.trustDocument().body().units()).isNotEmpty();
        assertThat(response.trustDocument().body().units().getFirst().evidence().evidenceSpanIds()).isNotEmpty();
        assertThat(response.sourceMap()).allSatisfy(ref -> {
            assertThat(ref.unitId()).isNotBlank();
            assertThat(ref.pageIndex()).isGreaterThanOrEqualTo(0);
            assertThat(ref.text()).isNotBlank();
        });
    }

    @Test
    void adjacentTableCaptionProjectsAsCaptionBlock() throws Exception {
        var response = new OpenDataLoaderJavaBackend()
                .parse(new OpenDataLoaderBackendRequest(writeCaptionedTablePdf(), ParserPreset.LITE));

        assertThat(response.blocks())
                .filteredOn(block -> "caption".equals(block.kind()))
                .extracting(OpenDataLoaderBlock::text)
                .contains("Table 1. Quarterly revenue by region");
    }

    private Path writePdf(String firstLine, String secondLine) throws Exception {
        var path = tempDir.resolve(firstLine.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase() + ".pdf");
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                content.newLineAtOffset(72, 720);
                content.showText(firstLine);
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(0, -28);
                content.showText(secondLine);
                content.endText();
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private Path writeCaptionedTablePdf() throws Exception {
        var path = tempDir.resolve("captioned-table.pdf");
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                writeText(content, "Table 1. Quarterly revenue by region", 72, 705);
                drawLine(content, 72, 680, 360, 680);
                drawLine(content, 72, 640, 360, 640);
                drawLine(content, 72, 600, 360, 600);
                drawLine(content, 72, 680, 72, 600);
                drawLine(content, 216, 680, 216, 600);
                drawLine(content, 360, 680, 360, 600);
                writeText(content, "Region", 100, 655);
                writeText(content, "Revenue", 245, 655);
                writeText(content, "North", 100, 615);
                writeText(content, "$10M", 245, 615);
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private static void writeText(PDPageContentStream stream, String text, float x, float y) throws Exception {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }

    private static void drawLine(PDPageContentStream stream, float x0, float y0, float x1, float y1) throws Exception {
        stream.moveTo(x0, y0);
        stream.lineTo(x1, y1);
        stream.stroke();
    }
}
