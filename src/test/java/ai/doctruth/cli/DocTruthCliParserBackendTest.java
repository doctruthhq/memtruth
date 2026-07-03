package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocTruthCliParserBackendTest {

    @TempDir
    Path tempDir;

    @Test
    void parseAcceptsPdfBoxParserOption() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"parse", pdf.toString(), "--parser", "pdfbox"});

        assertThat(code).isZero();
        assertThat(cli.out()).contains("parser: pdfbox").contains("pages: 1");
    }

    @Test
    void parseRejectsUnknownParserOption() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"parse", pdf.toString(), "--parser", "wat"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("unsupported parser: wat");
    }

    private static TestCli cli() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var cli = new DocTruthCli(
                Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                spec -> "{}",
                Providers::create);
        return new TestCli(cli, out, err);
    }

    private Path samplePdf() throws IOException {
        Path path = tempDir.resolve("contract.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 720);
                cs.showText("Party A: Acme Industrial Materials Pty Ltd");
                cs.endText();
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private record TestCli(DocTruthCli delegate, ByteArrayOutputStream outBytes, ByteArrayOutputStream errBytes) {
        int run(String[] args) {
            return delegate.run(args);
        }

        String err() {
            return errBytes.toString(StandardCharsets.UTF_8);
        }

        String out() {
            return outBytes.toString(StandardCharsets.UTF_8);
        }
    }
}
