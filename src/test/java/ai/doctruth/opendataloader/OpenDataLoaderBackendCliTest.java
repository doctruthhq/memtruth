package ai.doctruth.opendataloader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenDataLoaderBackendCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void jsonlBackendKeepsProcessAliveAcrossMultipleRequests() throws Exception {
        var first = writePdf("First persistent request");
        var second = writePdf("Second persistent request");
        var input = """
                {"document":"%s","preset":"lite"}
                {"document":"%s","preset":"lite"}
                """.formatted(first, second);
        var out = new ByteArrayOutputStream();

        int code = OpenDataLoaderBackendCli.run(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(code).isZero();
        var lines = out.toString(StandardCharsets.UTF_8).strip().split("\\R");
        assertThat(lines).hasSize(2);
        assertThat(MAPPER.readTree(lines[0]).path("ok").asBoolean()).isTrue();
        assertThat(MAPPER.readTree(lines[0]).path("markdown").asText()).contains("First persistent request");
        assertThat(MAPPER.readTree(lines[1]).path("markdown").asText()).contains("Second persistent request");
    }

    @Test
    void invalidRequestReturnsErrorAndNextRequestStillRuns() throws Exception {
        var valid = writePdf("Request after error");
        var input = """
                {"preset":"lite"}
                {"document":"%s","preset":"lite"}
                """.formatted(valid);
        var out = new ByteArrayOutputStream();

        int code = OpenDataLoaderBackendCli.run(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(code).isZero();
        var lines = out.toString(StandardCharsets.UTF_8).strip().split("\\R");
        assertThat(MAPPER.readTree(lines[0]).path("ok").asBoolean()).isFalse();
        assertThat(MAPPER.readTree(lines[0]).path("errorCode").asText()).isEqualTo("BACKEND_REQUEST_FAILED");
        assertThat(MAPPER.readTree(lines[1]).path("ok").asBoolean()).isTrue();
        assertThat(MAPPER.readTree(lines[1]).path("markdown").asText()).contains("Request after error");
    }

    private Path writePdf(String text) throws Exception {
        var path = tempDir.resolve(text.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase() + ".pdf");
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            doc.save(path.toFile());
        }
        return path;
    }
}
