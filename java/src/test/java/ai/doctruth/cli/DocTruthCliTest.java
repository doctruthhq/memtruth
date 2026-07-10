package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import ai.doctruth.LlmProvider;
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

class DocTruthCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void helpReturnsZeroAndListsProductCommands() {
        var cli = cliWithRealProviders(Map.of());

        int code = cli.run(new String[] {"--help"});

        assertThat(code).isZero();
        assertThat(cli.out())
                .contains("doctruth parse <document>")
                .contains("doctruth extract <document> -s <schema.json>")
                .doesNotContain("migrate pydantic");
    }

    @Test
    void versionReturnsZeroAndPrintsVersion() {
        var cli = cliWithRealProviders(Map.of());

        int code = cli.run(new String[] {"version"});

        assertThat(code).isZero();
        assertThat(cli.out()).contains("DocTruth").contains("0.2.0-alpha");
    }

    @Test
    void unknownCommandReturnsUsageError() {
        var cli = cliWithRealProviders(Map.of());

        int code = cli.run(new String[] {"wat"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("unknown command: wat").contains("doctruth --help");
    }

    @Test
    void initWritesDefaultConfigAndDirectories() throws Exception {
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"init", "--dir", tempDir.toString()});

        assertThat(code).isZero();
        assertThat(Files.readString(tempDir.resolve("doctruth.yml"))).contains("provider: openai");
        assertThat(Files.isDirectory(tempDir.resolve("schemas"))).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve(".doctruth/runs"))).isTrue();
    }

    @Test
    void initRejectsUnknownOption() {
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"init", "--wat"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("unknown init option");
    }

    @Test
    void parsePrintsSummaryWithoutLlmKey() throws Exception {
        Path pdf = samplePdf();
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"parse", pdf.toString(), "--bboxes"});

        assertThat(code).isZero();
        assertThat(cli.out()).contains("parser: opendataloader", "pages: 1", "sections:", "bbox coverage:");
    }

    @Test
    void parseJsonWritesStructuredSections() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("parsed.json");
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"parse", pdf.toString(), "--json", "-o", out.toString()});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(Files.readString(out));
        assertThat(tree.path("metadata").path("sourceFilename").asText())
                .isEqualTo(pdf.getFileName().toString());
        assertThat(tree.path("sections")).isNotEmpty();
    }

    @Test
    void parseRejectsUnsupportedFormat() throws Exception {
        Path file = tempDir.resolve("notes.txt");
        Files.writeString(file, "hello");
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"parse", file.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(cli.err()).contains("unsupported document format");
    }

    @Test
    void parseRejectsBadUsage() {
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"parse", "--json"});

        assertThat(code).isEqualTo(1);
        assertThat(cli.err()).contains("unsupported document format");
    }

    @Test
    void schemaChecksJsonSchemaByDefault() throws Exception {
        Path schema = schemaFile();
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"schema", schema.toString()});

        assertThat(code).isZero();
        assertThat(cli.out())
                .contains("schema compatible")
                .contains("fields: 2")
                .contains("required: 2");
    }

    @Test
    void schemaJsonPrintsMachineReadableSummary() throws Exception {
        Path schema = schemaFile();
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"schema", schema.toString(), "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("compatible").asBoolean()).isTrue();
        assertThat(tree.path("fieldCount").asInt()).isEqualTo(2);
    }

    @Test
    void schemaRejectsRemoteRefs() throws Exception {
        Path schema = tempDir.resolve("bad.schema.json");
        Files.writeString(schema, "{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"https://example.com/x\"}}}");
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"schema", schema.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(cli.err()).contains("schema compatibility check failed");
    }

    @Test
    void schemaRejectsUnknownOption() throws Exception {
        Path schema = schemaFile();
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"schema", schema.toString(), "--wat"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("unknown schema option");
    }

    @Test
    void extractWritesResultAndAuditToRunDirectory() throws Exception {
        Path pdf = samplePdf();
        Path schema = schemaFile();
        Path out = tempDir.resolve("run");
        var cli = cliWithProvider(cannedProvider());

        int code = cli.run(new String[] {"extract", pdf.toString(), "-s", schema.toString(), "-o", out.toString()});

        assertThat(code).isZero();
        assertThat(Files.readString(out.resolve("result.json"))).contains("Acme Industrial Materials Pty Ltd");
        assertThat(Files.readString(out.resolve("audit.json"))).contains("doctruth:fieldPath");
        assertThat(cli.out()).contains("fields: 2").contains("cited: 2").contains("audit:");
    }

    @Test
    void extractReportsMissingProviderKey() throws Exception {
        Path pdf = samplePdf();
        Path schema = schemaFile();
        var cli = cliWithRealProviders(Map.of());

        int code = cli.run(new String[] {"extract", pdf.toString(), "-s", schema.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(cli.err()).contains("missing OPENAI_API_KEY");
    }

    @Test
    void extractRejectsMissingSchemaOption() throws Exception {
        Path pdf = samplePdf();
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"extract", pdf.toString()});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("-s <schema.json> is required");
    }

    @Test
    void extractAllowUncitedDoesNotRequireFieldMatches() throws Exception {
        Path pdf = samplePdf();
        Path schema = schemaFile();
        Path out = tempDir.resolve("loose-run");
        var cli = cliWithProvider(new OpenAiProvider("test", URI.create("http://localhost"), "test-model") {
            @Override
            public ProviderResponse complete(ProviderRequest request) {
                return new ProviderResponse(
                        "{\"partyA\":\"not in source\",\"totalValue\":\"not in source\"}",
                        new ProviderUsage(1, 1, "test-model"));
            }
        });

        int code = cli.run(new String[] {
            "extract", pdf.toString(), "-s", schema.toString(), "-o", out.toString(), "--allow-uncited"
        });

        assertThat(code).isZero();
        assertThat(Files.readString(out.resolve("audit.json"))).contains("not in source");
    }

    @Test
    void auditPrintsReadableCitationSummary() throws Exception {
        Path pdf = samplePdf();
        Path schema = schemaFile();
        Path out = tempDir.resolve("run");
        var extract = cliWithProvider(cannedProvider());
        assertThat(extract.run(new String[] {"extract", pdf.toString(), "-s", schema.toString(), "-o", out.toString()}))
                .isZero();
        var audit = cliReturning("{}");

        int code = audit.run(new String[] {"audit", out.resolve("audit.json").toString()});

        assertThat(code).isZero();
        assertThat(audit.out()).contains("fields: 2").contains("partyA").contains("match:");
    }

    @Test
    void auditJsonPrintsMachineReadableSummary() throws Exception {
        Path pdf = samplePdf();
        Path schema = schemaFile();
        Path out = tempDir.resolve("run-json");
        var extract = cliWithProvider(cannedProvider());
        assertThat(extract.run(new String[] {"extract", pdf.toString(), "-s", schema.toString(), "-o", out.toString()}))
                .isZero();
        var audit = cliReturning("{}");

        int code = audit.run(new String[] {"audit", out.resolve("audit.json").toString(), "--json"});

        assertThat(code).isZero();
        assertThat(MAPPER.readTree(audit.out()).path("fields").asInt()).isEqualTo(2);
    }

    @Test
    void auditRejectsUnknownOption() {
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"audit", "x.json", "--wat"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("unknown audit option");
    }

    @Test
    void migratePydanticSupportsShortOutputOption() throws Exception {
        Path schemaPath = tempDir.resolve("resume.schema.json");
        var cli = cliReturning("{\"type\":\"object\"}");

        int code = cli.run(new String[] {"migrate", "pydantic", "myapp.schemas:Resume", "-o", schemaPath.toString()});

        assertThat(code).isZero();
        assertThat(Files.readString(schemaPath)).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void migratePydanticRejectsInvalidModelSpec() {
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {
            "migrate", "pydantic", "Resume", "-o", tempDir.resolve("x.json").toString()
        });

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("expected <module>:<Model>");
    }

    @Test
    void migratePydanticRejectsMissingOutput() {
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"migrate", "pydantic", "myapp.schemas:Resume"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("-o <schema.json> is required");
    }

    @Test
    void migratePydanticReportsInvalidExporterJson() {
        var cli = cliReturning("{not-json");

        int code = cli.run(new String[] {
            "migrate",
            "pydantic",
            "myapp.schemas:Resume",
            "-o",
            tempDir.resolve("bad.json").toString()
        });

        assertThat(code).isEqualTo(1);
        assertThat(cli.err()).contains("exported Pydantic schema is not valid JSON");
    }

    @Test
    void migratePydanticReportsExporterFailure() {
        var cli = cliWithExporter(spec -> {
            throw new IOException("python missing");
        });

        int code = cli.run(new String[] {
            "migrate",
            "pydantic",
            "myapp.schemas:Resume",
            "-o",
            tempDir.resolve("resume.json").toString()
        });

        assertThat(code).isEqualTo(1);
        assertThat(cli.err()).contains("failed to export Pydantic schema").contains("python missing");
    }

    @Test
    void migratePydanticRejectsRemoteRefsBeforeWritingOutput() {
        Path schemaPath = tempDir.resolve("bad.schema.json");
        var cli = cliReturning("""
                {
                  "type": "object",
                  "properties": {
                    "address": { "$ref": "https://example.com/schemas/address.json" }
                  }
                }
                """);

        int code = cli.run(
                new String[] {"migrate", "pydantic", "myapp.schemas:Resume", "-o", schemaPath.toString(), "--check"});

        assertThat(code).isEqualTo(1);
        assertThat(Files.exists(schemaPath)).isFalse();
        assertThat(cli.err()).contains("unsupported $ref");
    }

    @Test
    void pythonPydanticExporterUsesConfiguredExecutable() throws Exception {
        Path pythonStub = tempDir.resolve("python-stub");
        Files.writeString(pythonStub, """
                #!/bin/sh
                printf '{"type":"object","properties":{"name":{"type":"string"}}}'
                """, StandardCharsets.UTF_8);
        assertThat(pythonStub.toFile().setExecutable(true)).isTrue();

        var exporter = new DocTruthCli.PythonPydanticExporter(Map.of("DOCTRUTH_PYTHON", pythonStub.toString()));

        assertThat(exporter.export("myapp.schemas:Resume")).contains("\"name\"");
    }

    @Test
    void pythonPydanticExporterReportsNonZeroExit() throws Exception {
        Path pythonStub = tempDir.resolve("python-fail");
        Files.writeString(pythonStub, """
                #!/bin/sh
                printf 'no pydantic' >&2
                exit 13
                """, StandardCharsets.UTF_8);
        assertThat(pythonStub.toFile().setExecutable(true)).isTrue();
        var exporter = new DocTruthCli.PythonPydanticExporter(Map.of("DOCTRUTH_PYTHON", pythonStub.toString()));

        assertThatThrownBy(() -> exporter.export("myapp.schemas:Resume"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("python exited 13")
                .hasMessageContaining("no pydantic");
    }

    private TestCli cliReturning(String schemaJson) {
        return cliWithExporter(spec -> schemaJson);
    }

    private TestCli cliWithExporter(DocTruthCli.PydanticExporter exporter) {
        return cliWith(Map.of(), exporter, opts -> cannedProvider());
    }

    private TestCli cliWithProvider(LlmProvider provider) {
        return cliWith(Map.of("OPENAI_API_KEY", "test"), spec -> "{}", opts -> provider);
    }

    private TestCli cliWithRealProviders(Map<String, String> env) {
        return cliWith(env, spec -> "{}", Providers::create);
    }

    private TestCli cliWith(
            Map<String, String> env, DocTruthCli.PydanticExporter exporter, DocTruthCli.ProviderFactory providers) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var cli = new DocTruthCli(
                env,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                exporter,
                providers);
        return new TestCli(cli, out, err);
    }

    private static LlmProvider cannedProvider() {
        return new OpenAiProvider("test", URI.create("http://localhost"), "test-model") {
            @Override
            public ProviderResponse complete(ProviderRequest request) {
                return new ProviderResponse(
                        "{\"partyA\":\"Acme Industrial Materials Pty Ltd\",\"totalValue\":\"AUD 2,450,000\"}",
                        new ProviderUsage(1, 1, "test-model"));
            }
        };
    }

    private Path schemaFile() throws IOException {
        Path schema = tempDir.resolve("contract.schema.json");
        Files.writeString(schema, """
                {
                  "type": "object",
                  "properties": {
                    "partyA": { "type": "string" },
                    "totalValue": { "type": "string" }
                  },
                  "required": ["partyA", "totalValue"],
                  "additionalProperties": false
                }
                """);
        return schema;
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
                cs.newLineAtOffset(0, -18);
                cs.showText("Total Value: AUD 2,450,000");
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
