package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkOracleCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void missingOpenDataLoaderHybridOracleDependencyGivesDoctorHint() throws Exception {
        Path pdf = samplePdf();
        var cli = cli(Map.of());

        int code = cli.run(
                new String[] {"benchmark-oracle", "--engine", "opendataloader-hybrid", pdf.toString(), "--json"});

        assertThat(code).isEqualTo(1);
        assertThat(cli.err())
                .contains("opendataloader-hybrid oracle unavailable")
                .contains("DOCTRUTH_OPENDATALOADER_HYBRID_ORACLE_COMMAND")
                .contains("doctruth doctor");
    }

    @Test
    void openDataLoaderHybridOracleEmitsTrustDocumentWithProvenance() throws Exception {
        Path pdf = samplePdf();
        Path oracle = fakeHybridOracle();
        var cli = cli(Map.of("DOCTRUTH_OPENDATALOADER_HYBRID_ORACLE_COMMAND", oracle.toString()));

        int code = cli.run(
                new String[] {"benchmark-oracle", "--engine", "opendataloader-hybrid", pdf.toString(), "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("parserRun").path("backend").asText()).isEqualTo("opendataloader-hybrid-oracle");
        assertThat(tree.path("parserRun").path("externalBackend").path("name").asText())
                .isEqualTo("opendataloader-pdf");
        assertThat(tree.path("parserRun")
                        .path("externalBackend")
                        .path("version")
                        .asText())
                .isEqualTo("2.2.1");
        assertThat(tree.path("parserRun")
                        .path("externalBackend")
                        .path("doclingVersion")
                        .asText())
                .isEqualTo("2.84.0");
        assertThat(tree.path("parserRun").path("elapsedMs").asLong()).isEqualTo(1234);
        assertThat(tree.path("auditGradeStatus").asText()).isEqualTo("NOT_AUDIT_GRADE");
        assertThat(tree.path("parserRun").path("warnings").get(0).path("code").asText())
                .isEqualTo("opendataloader_markdown_only_source_mapping");
        assertThat(tree.path("body").path("units").get(0).path("text").asText()).isEqualTo("Hybrid Oracle Title");
    }

    @Test
    void openDataLoaderHybridOracleCommandMayIncludeInterpreterAndScript() throws Exception {
        Path pdf = samplePdf();
        Path interpreter = fakeInterpreterOracle();
        Path script = tempDir.resolve("oracle-script.py");
        Files.writeString(script, "# marker\n", StandardCharsets.UTF_8);
        var cli = cli(Map.of("DOCTRUTH_OPENDATALOADER_HYBRID_ORACLE_COMMAND", interpreter + " " + script));

        int code = cli.run(
                new String[] {"benchmark-oracle", "--engine", "opendataloader-hybrid", pdf.toString(), "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("body").path("units").get(0).path("text").asText()).isEqualTo("Interpreter Oracle Title");
        assertThat(tree.path("parserRun").path("externalBackend").path("mode").asText())
                .isEqualTo("docling-fast");
    }

    @Test
    void openDataLoaderHybridOraclePrefersStructuredBlocksOverMarkdown() throws Exception {
        Path pdf = samplePdf();
        Path oracle = fakeStructuredHybridOracle();
        var cli = cli(Map.of("DOCTRUTH_OPENDATALOADER_HYBRID_ORACLE_COMMAND", oracle.toString()));

        int code = cli.run(
                new String[] {"benchmark-oracle", "--engine", "opendataloader-hybrid", pdf.toString(), "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("auditGradeStatus").asText()).isEqualTo("AUDIT_GRADE");
        assertThat(tree.path("parserRun").path("warnings").get(0).path("code").asText())
                .isEqualTo("opendataloader_structured_source_mapping");
        assertThat(tree.path("body").path("units").findValuesAsText("text"))
                .contains("Structured Profile", "First item", "Second item", "Name", "Alex");
        var table = tree.path("body").path("tables").get(0);
        assertThat(table.path("cells")).hasSize(4);
        assertThat(table.path("cells").findValuesAsText("text")).contains("Name", "Score", "Alex", "98");
    }

    @Test
    void openDataLoaderHybridOracleContentBlocksPreserveHeadingAndListShape() throws Exception {
        Path pdf = samplePdf();
        Path oracle = fakeStructuredHybridOracle();
        var cli = cli(Map.of("DOCTRUTH_OPENDATALOADER_HYBRID_ORACLE_COMMAND", oracle.toString()));

        int code = cli.run(new String[] {
            "benchmark-oracle", "--engine", "opendataloader-hybrid", pdf.toString(), "--format", "content_blocks"
        });

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("format").asText()).isEqualTo("doctruth.content_blocks.v1");
        var blocks = tree.path("contentBlocks");
        assertThat(blocks.get(0).path("type").asText()).isEqualTo("heading");
        assertThat(blocks.get(0).path("textLevel").asInt()).isEqualTo(2);
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("list");
        assertThat(blocks.get(1).path("items").findValuesAsText("text")).containsExactly("First item", "Second item");
        assertThat(blocks.get(2).path("type").asText()).isEqualTo("table");
        assertThat(blocks.get(2).path("rows").get(1).findValuesAsText("text")).containsExactly("Alex", "98");
    }

    @Test
    void productionParseRejectsOpenDataLoaderHybridAsBackend() throws Exception {
        Path pdf = samplePdf();
        var cli = cli(Map.of());

        int code = cli.run(new String[] {"parse", pdf.toString(), "--backend", "opendataloader-hybrid", "--json"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("unknown parser backend").contains("opendataloader-hybrid");
    }

    private Path samplePdf() throws IOException {
        Path path = tempDir.resolve("oracle.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path fakeHybridOracle() throws IOException {
        Path oracle = tempDir.resolve("fake-opendataloader-hybrid-oracle");
        Files.writeString(oracle, """
                #!/usr/bin/env sh
                cat <<'JSON'
                {
                  "markdown": "# Hybrid Oracle Title\\n\\nHybrid body from OpenDataLoader.",
                  "elapsedMs": 1234,
                  "externalBackend": {
                    "name": "opendataloader-pdf",
                    "version": "2.2.1",
                    "doclingVersion": "2.84.0",
                    "mode": "docling-fast",
                    "serverUrl": "http://127.0.0.1:5002",
                    "rssMb": "1510"
                  }
                }
                JSON
                """, StandardCharsets.UTF_8);
        assertThat(oracle.toFile().setExecutable(true)).isTrue();
        return oracle;
    }

    private Path fakeStructuredHybridOracle() throws IOException {
        Path oracle = tempDir.resolve("fake-structured-opendataloader-hybrid-oracle");
        Files.writeString(oracle, """
                #!/usr/bin/env sh
                cat <<'JSON'
                {
                  "markdown": "# Markdown Fallback Should Not Win",
                  "elapsedMs": 321,
                  "externalBackend": {
                    "name": "opendataloader-pdf",
                    "version": "2.2.1",
                    "doclingVersion": "2.84.0",
                    "mode": "docling-fast"
                  },
                  "blocks": [
                    {
                      "blockId": "odl-heading-1",
                      "type": "heading",
                      "text": "Structured Profile",
                      "textLevel": 2,
                      "page": 1,
                      "readingOrder": 1,
                      "bbox": [10, 20, 300, 60]
                    },
                    {
                      "blockId": "odl-list-1",
                      "type": "list",
                      "page": 1,
                      "readingOrder": 2,
                      "items": ["First item", "Second item"]
                    },
                    {
                      "blockId": "odl-table-1",
                      "type": "table",
                      "page": 1,
                      "readingOrder": 3,
                      "rows": [
                        ["Name", "Score"],
                        ["Alex", "98"]
                      ]
                    }
                  ]
                }
                JSON
                """, StandardCharsets.UTF_8);
        assertThat(oracle.toFile().setExecutable(true)).isTrue();
        return oracle;
    }

    private Path fakeInterpreterOracle() throws IOException {
        Path oracle = tempDir.resolve("fake-oracle-interpreter");
        Files.writeString(oracle, """
                #!/usr/bin/env sh
                test -f "$1"
                test -f "$2"
                cat <<'JSON'
                {
                  "markdown": "# Interpreter Oracle Title\\n\\nInterpreter body.",
                  "elapsedMs": 42,
                  "externalBackend": {
                    "name": "opendataloader-pdf",
                    "version": "2.2.1",
                    "doclingVersion": "2.84.0",
                    "mode": "docling-fast"
                  }
                }
                JSON
                """, StandardCharsets.UTF_8);
        assertThat(oracle.toFile().setExecutable(true)).isTrue();
        return oracle;
    }

    private static TestCli cli(Map<String, String> env) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var cli = new DocTruthCli(
                env,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                spec -> "{}",
                options -> cannedProvider());
        return new TestCli(cli, out, err);
    }

    private static OpenAiProvider cannedProvider() {
        return new OpenAiProvider("test", URI.create("http://localhost"), "test-model") {
            @Override
            public ProviderResponse complete(ProviderRequest request) {
                return new ProviderResponse("{}", new ProviderUsage(1, 1, "test-model"));
            }
        };
    }

    private static final class TestCli {
        private final DocTruthCli cli;
        private final ByteArrayOutputStream out;
        private final ByteArrayOutputStream err;

        private TestCli(DocTruthCli cli, ByteArrayOutputStream out, ByteArrayOutputStream err) {
            this.cli = cli;
            this.out = out;
            this.err = err;
        }

        int run(String[] args) {
            return cli.run(args);
        }

        String out() {
            return out.toString(StandardCharsets.UTF_8);
        }

        String err() {
            return err.toString(StandardCharsets.UTF_8);
        }
    }
}
