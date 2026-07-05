package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import ai.doctruth.OpenAiProvider;
import ai.doctruth.ProviderRequest;
import ai.doctruth.ProviderResponse;
import ai.doctruth.ProviderUsage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocTruthCliTrustDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void parseFormatJsonWritesTrustDocument() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("trust-document.json");
        var cli = cli();

        int code = cli.run(new String[] {"parse", pdf.toString(), "--format", "json", "-o", out.toString()});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(Files.readString(out));
        assertThat(tree.path("schemaVersion").asText()).isEqualTo("doctruth.trust-document.v1");
        assertThat(tree.path("source").path("filename").asText())
                .isEqualTo(pdf.getFileName().toString());
        assertThat(tree.path("source").path("sha256").asText()).hasSize(64);
        assertThat(tree.path("parserRun").path("backend").asText()).isEqualTo("opendataloader");
        assertThat(tree.path("units")).isNotEmpty();
        assertThat(tree.path("units").get(0).path("location").path("pageStart").asInt())
                .isEqualTo(1);
    }

    @Test
    void parseFormatJsonPrintsTrustDocument() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"parse", pdf.toString(), "--format", "json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("schemaVersion").asText()).isEqualTo("doctruth.trust-document.v1");
        assertThat(tree.path("parserRun").path("backend").asText()).isEqualTo("opendataloader");
    }

    @Test
    void parseFormatParsedJsonPrintsCompatibilityJson() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"parse", pdf.toString(), "--format", "parsed-json", "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("metadata").path("sourceFilename").asText())
                .isEqualTo(pdf.getFileName().toString());
        assertThat(tree.path("sections")).isNotEmpty();
    }

    @Test
    void profileJsonReportsParserLatencyAndMemory() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"profile", pdf.toString(), "--iterations", "1", "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("parser").asText()).isEqualTo("opendataloader");
        assertThat(tree.path("iterations").asInt()).isEqualTo(1);
        assertThat(tree.path("coldLatencyMillis").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(tree.path("fileSizeBytes").asLong()).isEqualTo(Files.size(pdf));
        assertThat(tree.path("sectionCount").asInt()).isGreaterThan(0);
        assertThat(tree.path("includeOutput").asText()).isEqualTo("parser-only");
        assertThat(tree.path("parseLatencyMillis")).hasSize(1);
        assertThat(tree.path("heapUsedBeforeBytes").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(tree.path("heapUsedAfterBytes").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void profileCanIncludeTrustJsonOutputCost() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(
                new String[] {"profile", pdf.toString(), "--iterations", "1", "--include-output", "trust-json", "--json"
                });

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("includeOutput").asText()).isEqualTo("trust-json");
        assertThat(tree.path("outputLatencyMillis")).hasSize(1);
        assertThat(tree.path("coldOutputLatencyMillis").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void profileCanIncludeParsedJsonOutputCost() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {
            "profile", pdf.toString(), "--iterations", "1", "--include-output", "parsed-json", "--json"
        });

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("includeOutput").asText()).isEqualTo("parsed-json");
        assertThat(tree.path("outputLatencyMillis")).hasSize(1);
    }

    @Test
    void profileCanIncludeTrustJsonFileOutputCost() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {
            "profile", pdf.toString(), "--iterations", "1", "--include-output", "trust-json-file", "--json"
        });

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("includeOutput").asText()).isEqualTo("trust-json-file");
        assertThat(tree.path("outputLatencyMillis")).hasSize(1);
        assertThat(tree.path("profiledOutputBytes").asLong()).isGreaterThan(0);
        assertThat(tree.path("profiledOutputChars").asLong()).isZero();
    }

    @Test
    void profileCanIncludeParsedJsonFileOutputCost() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {
            "profile", pdf.toString(), "--iterations", "1", "--include-output", "parsed-json-file", "--json"
        });

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("includeOutput").asText()).isEqualTo("parsed-json-file");
        assertThat(tree.path("outputLatencyMillis")).hasSize(1);
        assertThat(tree.path("profiledOutputBytes").asLong()).isGreaterThan(0);
        assertThat(tree.path("profiledOutputChars").asLong()).isZero();
    }

    @Test
    void profilePrintsReadableSummaryWithWarmRun() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"profile", pdf.toString(), "--iterations", "2"});

        assertThat(code).isZero();
        assertThat(cli.out())
                .contains("parser: opendataloader")
                .contains("iterations: 2")
                .contains("file size bytes:")
                .contains("sections:")
                .contains("warm avg latency ms:");
    }

    @Test
    void profilePrintsOutputTimingSummaryWhenIncludingOutput() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(
                new String[] {"profile", pdf.toString(), "--iterations", "1", "--include-output", "trust-json"});

        assertThat(code).isZero();
        assertThat(cli.out())
                .contains("include output: trust-json")
                .contains("cold output latency ms:")
                .contains("warm avg output latency ms:");
    }

    @Test
    void profileRejectsUnknownOutputMode() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"profile", pdf.toString(), "--include-output", "xml"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("unsupported profile output mode");
    }

    @Test
    void profileRejectsBadUsage() {
        var cli = cli();

        int code = cli.run(new String[] {"profile"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("usage: doctruth profile");
    }

    @Test
    void profileRejectsUnknownOption() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"profile", pdf.toString(), "--wat"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("unknown profile option");
    }

    @Test
    void profileRejectsUnknownParser() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"profile", pdf.toString(), "--parser", "wat"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("unsupported parser");
    }

    @Test
    void profileRejectsInvalidIterations() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"profile", pdf.toString(), "--iterations", "0"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("--iterations must be >= 1");
    }

    @Test
    void profileRejectsNonNumericIterations() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"profile", pdf.toString(), "--iterations", "many"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("--iterations must be an integer");
    }

    @Test
    void profileRejectsMissingOptionValue() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"profile", pdf.toString(), "--parser"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("--parser requires a value");
    }

    private static TestCli cli() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var provider = new OpenAiProvider("test", URI.create("http://localhost"), "test-model") {
            @Override
            public ProviderResponse complete(ProviderRequest request) {
                return new ProviderResponse("{}", new ProviderUsage(1, 1, "test-model"));
            }
        };
        var cli = new DocTruthCli(
                Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                spec -> "{}",
                opts -> provider);
        return new TestCli(cli, out, err);
    }

    private Path samplePdf() throws Exception {
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

        String out() {
            return outBytes.toString(StandardCharsets.UTF_8);
        }

        String err() {
            return errBytes.toString(StandardCharsets.UTF_8);
        }
    }
}
