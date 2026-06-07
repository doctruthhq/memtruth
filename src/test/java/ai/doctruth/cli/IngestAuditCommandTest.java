package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

class IngestAuditCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void ingestAuditJsonReportsTextLayerAndEvidenceCategoriesWithoutRawText() throws Exception {
        Path corpus = tempDir.resolve("pdfs");
        Files.createDirectories(corpus);
        writePdf(corpus.resolve("text.pdf"), "WORK EXPERIENCE", "Built conveyors and maintained PLC systems.");
        writeBlankPdf(corpus.resolve("scan.pdf"));
        var cli = cli();

        int code = cli.run(new String[] {"ingest-audit", corpus.toString(), "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("totalFiles").asInt()).isEqualTo(2);
        assertThat(tree.path("parsed").asInt()).isEqualTo(2);
        assertThat(tree.path("issueSummary").path("doctruth_text").asInt()).isEqualTo(1);
        assertThat(tree.path("files").get(0).path("findings").toString()).doesNotContain("Built conveyors");
    }

    @Test
    void ingestAuditReadableSummaryShowsTopLevelCounts() throws Exception {
        Path corpus = tempDir.resolve("pdfs");
        Files.createDirectories(corpus);
        writePdf(corpus.resolve("resume.pdf"), "EDUCATION", "Diploma in Mechanical Engineering");
        var cli = cli();

        int code = cli.run(new String[] {"ingest-audit", corpus.toString()});

        assertThat(code).isZero();
        assertThat(cli.out())
                .contains("ingest audit")
                .contains("total files: 1")
                .contains("parsed: 1")
                .contains("doctruth_text:");
    }

    @Test
    void ingestAuditLimitBoundsLargeCorpusRuns() throws Exception {
        Path corpus = tempDir.resolve("pdfs");
        Files.createDirectories(corpus);
        writePdf(corpus.resolve("a.pdf"), "A", "Alpha");
        writePdf(corpus.resolve("b.pdf"), "B", "Beta");
        var cli = cli();

        int code = cli.run(new String[] {"ingest-audit", corpus.toString(), "--json", "--limit", "1"});

        assertThat(code).isZero();
        assertThat(MAPPER.readTree(cli.out()).path("totalFiles").asInt()).isEqualTo(1);
    }

    @Test
    void ingestAuditWritesJsonFileAndPrintsOutputPath() throws Exception {
        Path corpus = tempDir.resolve("pdfs");
        Files.createDirectories(corpus);
        writePdf(corpus.resolve("resume.pdf"), "SKILLS", "Forklift and warehouse inventory");
        Path out = tempDir.resolve("reports/ingest-audit.json");
        var cli = cli();

        int code = cli.run(new String[] {"ingest-audit", corpus.toString(), "--json", "-o", out.toString()});

        assertThat(code).isZero();
        assertThat(cli.out()).contains("output: " + out);
        assertThat(MAPPER.readTree(Files.readString(out)).path("totalFiles").asInt()).isEqualTo(1);
    }

    @Test
    void ingestAuditReportsBadPdfAsParseFinding() throws Exception {
        Path corpus = tempDir.resolve("pdfs");
        Files.createDirectories(corpus);
        Files.writeString(corpus.resolve("bad.pdf"), "not a pdf");
        var cli = cli();

        int code = cli.run(new String[] {"ingest-audit", corpus.toString(), "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("failed").asInt()).isEqualTo(1);
        assertThat(tree.path("issueSummary").path("doctruth_parse").asInt()).isEqualTo(1);
        assertThat(tree.path("files").get(0).path("status").asText()).isEqualTo("parse_failed");
    }

    @Test
    void ingestAuditRejectsBadUsageAndInvalidRoot() {
        var cli = cli();

        assertThat(cli.run(new String[] {"ingest-audit"})).isEqualTo(2);
        assertThat(cli.err()).contains("usage: doctruth ingest-audit");

        var missingRoot = cli();
        assertThat(missingRoot.run(new String[] {"ingest-audit", tempDir.resolve("missing").toString()})).isEqualTo(1);
        assertThat(missingRoot.err()).contains("ingest audit root is not a directory");
    }

    @Test
    void ingestAuditRejectsInvalidLimitAndUnknownOption() {
        Path corpus = tempDir.resolve("pdfs");
        var cli = cli();

        assertThat(cli.run(new String[] {"ingest-audit", corpus.toString(), "--limit", "0"})).isEqualTo(2);
        assertThat(cli.err()).contains("--limit requires a positive integer");

        var unknown = cli();
        assertThat(unknown.run(new String[] {"ingest-audit", corpus.toString(), "--wat"})).isEqualTo(2);
        assertThat(unknown.err()).contains("unknown ingest-audit option");
    }

    private TestCli cli() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var cli = new DocTruthCli(
                Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                spec -> "{}",
                opts -> {
                    throw new AssertionError("ingest audit must not create LLM providers");
                });
        return new TestCli(cli, out, err);
    }

    private static void writePdf(Path path, String heading, String body) throws IOException {
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(50, 720);
                cs.showText(heading);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(0, -18);
                cs.showText(body);
                cs.endText();
            }
            pdf.save(path.toFile());
        }
    }

    private static void writeBlankPdf(Path path) throws IOException {
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(path.toFile());
        }
    }

    private record TestCli(DocTruthCli delegate, ByteArrayOutputStream outBytes, ByteArrayOutputStream errBytes) {
        int run(String[] args) {
            return delegate.run(args);
        }

        String out() {
            return outBytes.toString(StandardCharsets.UTF_8);
        }

        String err() {
            return errBytes.toString(StandardCharsets.UTF_8);
        }
    }
}
