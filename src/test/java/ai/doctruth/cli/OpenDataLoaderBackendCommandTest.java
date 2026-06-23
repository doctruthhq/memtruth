package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenDataLoaderBackendCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void cliCommandRunsStdioJsonlBackend() throws Exception {
        var pdf = writePdf("CLI stdio backend");
        var input = """
                {"document":"%s","preset":"lite"}
                """.formatted(pdf);
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var cli = new DocTruthCli(
                Map.of(),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                spec -> "{}",
                options -> null);

        int code = cli.run(new String[] {"opendataloader-backend", "--stdio-jsonl"});

        assertThat(code).isZero();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isBlank();
        var root = MAPPER.readTree(stdout.toString(StandardCharsets.UTF_8));
        assertThat(root.path("ok").asBoolean()).isTrue();
        assertThat(root.path("backend").asText()).isEqualTo("opendataloader-java-core");
        assertThat(root.path("markdown").asText()).contains("CLI stdio backend");
    }

    private Path writePdf(String text) throws Exception {
        var path = tempDir.resolve("cli-backend.pdf");
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
