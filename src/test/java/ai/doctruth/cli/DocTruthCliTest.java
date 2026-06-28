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
                .contains("doctruth benchmark-corpus <manifest.json>")
                .contains("doctruth ingest-audit <pdf-dir>")
                .contains("doctruth extract <document> -s <schema.json>")
                .contains("doctruth mcp")
                .contains("doctruth verify-audit <trust-document.json> <audit.json>")
                .contains("doctruth verify-benchmark-report <report.json>")
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
        assertThat(cli.out())
                .contains("pages: 1")
                .contains("units:")
                .contains("parser backend: rust-sidecar")
                .contains("audit grade:");
    }

    @Test
    void parseJsonWritesRustTrustDocumentByDefault() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("parsed.json");
        Path runtime = fakeSidecarRuntime();
        var cli = cliWithRealProviders(Map.of("DOCTRUTH_RUNTIME_COMMAND", runtime.toString()));

        int code = cli.run(new String[] {"parse", pdf.toString(), "--json", "-o", out.toString()});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(Files.readString(out));
        assertThat(tree.path("docId").asText()).isEqualTo("sha256:cli-sidecar");
        assertThat(tree.path("parserRun").path("backend").asText()).isEqualTo("sidecar");
        assertThat(tree.path("body").path("units").get(0).path("text").asText()).isEqualTo("Parsed by CLI sidecar.");
    }

    @Test
    void parseMarkdownWritesRustTrustDocumentByDefault() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("parsed.md");
        Path runtime = fakeSidecarRuntime();
        var cli = cliWithRealProviders(Map.of("DOCTRUTH_RUNTIME_COMMAND", runtime.toString()));

        int code = cli.run(new String[] {"parse", pdf.toString(), "--markdown", "-o", out.toString()});

        assertThat(code).isZero();
        assertThat(Files.readString(out)).contains("Parsed by CLI sidecar.").doesNotContain("Acme Industrial");
    }

    @Test
    void parseLegacyJsonRequiresExplicitPdfboxBackend() throws Exception {
        Path pdf = samplePdf();
        var implicit = cliReturning("{}");
        var explicit = cliReturning("{}");
        Path out = tempDir.resolve("legacy.json");

        int implicitCode = implicit.run(new String[] {"parse", pdf.toString(), "--format", "legacy-json"});
        int explicitCode = explicit.run(new String[] {
            "parse", pdf.toString(), "--backend", "pdfbox", "--format", "legacy-json", "-o", out.toString()
        });

        assertThat(implicitCode).isEqualTo(2);
        assertThat(implicit.err()).contains("legacy parse output requires --backend pdfbox");
        assertThat(explicitCode).isZero();
        var tree = MAPPER.readTree(Files.readString(out));
        assertThat(tree.path("metadata").path("sourceFilename").asText())
                .isEqualTo(pdf.getFileName().toString());
        assertThat(tree.path("sections")).isNotEmpty();
    }

    @Test
    void parseLegacyMarkdownRequiresExplicitPdfboxBackend() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("legacy.md");
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {
            "parse", pdf.toString(), "--backend", "pdfbox", "--format", "legacy-markdown", "-o", out.toString()
        });

        assertThat(code).isZero();
        assertThat(Files.readString(out)).contains("Acme Industrial Materials Pty Ltd");
    }

    @Test
    void renderPagesWritesPngArtifactsAndManifest() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("page-images");
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"render-pages", pdf.toString(), "-o", out.toString()});

        assertThat(code).isZero();
        assertThat(Files.exists(out.resolve("page-0001.png"))).isTrue();
        assertThat(Files.readAllBytes(out.resolve("page-0001.png")))
                .startsWith(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
        var manifest = MAPPER.readTree(Files.readString(out.resolve("page-images.json")));
        assertThat(manifest.path("sourceFilename").asText())
                .isEqualTo(pdf.getFileName().toString());
        assertThat(manifest.path("pages")).hasSize(1);
        assertThat(manifest.path("pages").get(0).path("imageHash").asText()).startsWith("sha256:");
        assertThat(cli.out()).contains("pages: 1").contains("page-images:");
    }

    @Test
    void reviewPackageWritesHtmlDocumentAndPageImages() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("review-package");
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"review-package", pdf.toString(), "-o", out.toString()});

        assertThat(code).isZero();
        assertThat(Files.exists(out.resolve("trust-document.json"))).isTrue();
        assertThat(Files.readString(out.resolve("review.html")))
                .contains("pages/page-0001.png")
                .contains("data-trust-page-number=\"1\"")
                .contains("data-trust-review-package=\"doctruth\"");
        assertThat(Files.readAllBytes(out.resolve("pages/page-0001.png")))
                .startsWith(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
        var manifest = MAPPER.readTree(Files.readString(out.resolve("pages/page-images.json")));
        assertThat(manifest.path("pages")).hasSize(1);
        var trust = MAPPER.readTree(Files.readString(out.resolve("trust-document.json")));
        assertThat(trust.path("body").path("pages").get(0).path("imageHash").asText())
                .isEqualTo(manifest.path("pages").get(0).path("imageHash").asText());
        assertThat(cli.out()).contains("review-package:").contains("pages: 1");
    }

    @Test
    void reviewPackageWritesTraceLinkedDebugArtifacts() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("trace-review-package");
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"review-package", pdf.toString(), "-o", out.toString()});

        assertThat(code).isZero();
        assertThat(Files.exists(out.resolve("content_blocks.json"))).isTrue();
        assertThat(Files.exists(out.resolve("parse_trace.json"))).isTrue();
        assertThat(Files.exists(out.resolve("layout-debug.html"))).isTrue();
        assertThat(Files.exists(out.resolve("span-debug.html"))).isTrue();

        var trace = MAPPER.readTree(Files.readString(out.resolve("parse_trace.json")));
        var block = trace.path("parseTrace")
                .path("pages")
                .get(0)
                .path("readingBlocks")
                .get(0);
        String blockId = block.path("blockId").asText();
        String lineId = block.path("lines").get(0).path("lineId").asText();
        String spanId =
                block.path("lines").get(0).path("spans").get(0).path("spanId").asText();

        assertThat(Files.readString(out.resolve("layout-debug.html")))
                .contains("data-doctruth-debug-artifact=\"layout\"")
                .contains("data-trace-block-id=\"" + blockId + "\"");
        assertThat(Files.readString(out.resolve("span-debug.html")))
                .contains("data-doctruth-debug-artifact=\"span\"")
                .contains("data-trace-block-id=\"" + blockId + "\"")
                .contains("data-trace-line-id=\"" + lineId + "\"")
                .contains("data-trace-span-id=\"" + spanId + "\"");
    }

    @Test
    void reviewPackageRejectsMissingOutAndUnknownOptions() throws Exception {
        Path pdf = samplePdf();
        var missingOut = cliReturning("{}");
        var unknown = cliReturning("{}");

        int missingOutCode = missingOut.run(new String[] {"review-package", pdf.toString()});
        int unknownCode = unknown.run(new String[] {
            "review-package", pdf.toString(), "-o", tempDir.resolve("review").toString(), "--wat"
        });

        assertThat(missingOutCode).isEqualTo(2);
        assertThat(missingOut.err()).contains("requires -o");
        assertThat(unknownCode).isEqualTo(2);
        assertThat(unknown.err()).contains("unknown review-package option");
    }

    @Test
    void reviewPackageCanUseOcrPresetWithConfiguredLocalWorker() throws Exception {
        Path pdf = blankPdf();
        Path out = tempDir.resolve("ocr-review-package");
        Path worker = fakeOcrWorker("""
                {"ok":true,"engine":"mnn","text":"OCR package text","averageConfidence":0.92,"pages":[],"warnings":[]}
                """);
        Path runtime = fakeOcrRuntime(worker, 0.92, "OCR package text");
        var cli = cliReturning("{}");

        withSystemProperties(
                Map.of("doctruth.runtime.command", runtime.toString(), "doctruth.ocr.command", worker.toString()),
                () -> {
                    int code = cli.run(
                            new String[] {"review-package", pdf.toString(), "--preset", "ocr", "-o", out.toString()});

                    assertThat(code).isZero();
                    var trust = MAPPER.readTree(Files.readString(out.resolve("trust-document.json")));
                    assertThat(trust.path("parserRun").path("backend").asText()).isEqualTo("rust-sidecar+model-worker");
                    assertThat(trust.path("parserRun").path("models").toString())
                            .contains("ocr-router:v1");
                    assertThat(trust.path("body")
                                    .path("units")
                                    .get(0)
                                    .path("kind")
                                    .asText())
                            .isEqualTo("OCR_REGION");
                    assertThat(Files.readString(out.resolve("review.html"))).contains("OCR package text");
                });
    }

    @Test
    void parseTrustJsonCanUseOcrPresetWithConfiguredLocalWorker() throws Exception {
        Path pdf = blankPdf();
        Path out = tempDir.resolve("ocr-trust.json");
        Path worker = fakeOcrWorker("""
                {"ok":true,"engine":"mnn","text":"OCR parse text","averageConfidence":0.94,"pages":[],"warnings":[]}
                """);
        Path runtime = fakeOcrRuntime(worker, 0.94, "OCR parse text");
        var cli = cliReturning("{}");

        withSystemProperties(
                Map.of("doctruth.runtime.command", runtime.toString(), "doctruth.ocr.command", worker.toString()),
                () -> {
                    int code = cli.run(new String[] {
                        "parse", pdf.toString(), "--format", "json", "--preset", "ocr", "-o", out.toString()
                    });

                    assertThat(code).isZero();
                    var trust = MAPPER.readTree(Files.readString(out));
                    assertThat(trust.path("parserRun").path("backend").asText()).isEqualTo("rust-sidecar+model-worker");
                    assertThat(trust.path("body")
                                    .path("units")
                                    .get(0)
                                    .path("kind")
                                    .asText())
                            .isEqualTo("OCR_REGION");
                    assertThat(trust.path("body")
                                    .path("units")
                                    .get(0)
                                    .path("text")
                                    .asText())
                            .isEqualTo("OCR parse text");
                });
    }

    @Test
    void parseTrustJsonMarksLowConfidenceOcrAsNotAuditGrade() throws Exception {
        Path pdf = blankPdf();
        Path out = tempDir.resolve("low-confidence-ocr-trust.json");
        Path worker = fakeOcrWorker("""
                {"ok":true,"engine":"mnn","text":"Weak OCR parse text","averageConfidence":0.41,"pages":[],"warnings":[]}
                """);
        Path runtime = fakeOcrRuntime(worker, 0.41, "Weak OCR parse text");
        var cli = cliReturning("{}");

        withSystemProperties(
                Map.of("doctruth.runtime.command", runtime.toString(), "doctruth.ocr.command", worker.toString()),
                () -> {
                    int code = cli.run(new String[] {
                        "parse", pdf.toString(), "--format", "json", "--preset", "ocr", "-o", out.toString()
                    });

                    assertThat(code).isZero();
                    var trust = MAPPER.readTree(Files.readString(out));
                    var unit = trust.path("body").path("units").get(0);
                    assertThat(trust.path("auditGradeStatus").asText()).isEqualTo("NOT_AUDIT_GRADE");
                    assertThat(unit.path("confidence").path("score").asDouble()).isEqualTo(0.41);
                    assertThat(unit.path("warnings").get(0).path("code").asText())
                            .isEqualTo("ocr_low_confidence");
                    assertThat(unit.path("warnings").get(0).path("severity").asText())
                            .isEqualTo("SEVERE");
                });
    }

    @Test
    void parseMarkdownRoutesLowTextPdfThroughConfiguredLocalOcr() throws Exception {
        Path pdf = blankPdf();
        Path out = tempDir.resolve("ocr.md");
        Path worker = fakeOcrWorker("""
                {"ok":true,"engine":"mnn","text":"OCR recovered scanned resume","averageConfidence":0.91,"pages":[],"warnings":[]}
                """);
        Path runtime = fakeOcrRuntime(worker, 0.91, "OCR recovered scanned resume");
        var cli = cliReturning("{}");

        withSystemProperties(
                Map.of("doctruth.runtime.command", runtime.toString(), "doctruth.ocr.command", worker.toString()),
                () -> {
                    int code = cli.run(new String[] {"parse", pdf.toString(), "--markdown", "-o", out.toString()});

                    assertThat(code).isZero();
                    assertThat(Files.readString(out)).contains("OCR recovered scanned resume");
                });
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

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("parse requires <document>");
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

    private Path blankPdf() throws IOException {
        Path path = tempDir.resolve("blank.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path fakeSidecarRuntime() throws IOException {
        Path runtime = tempDir.resolve("fake-doctruth-runtime");
        Files.writeString(runtime, """
                #!/usr/bin/env sh
                cat >/dev/null
                cat <<'JSON'
                {"docId":"sha256:cli-sidecar","source":{"sourceFilename":"contract.pdf","sourceHash":"sha256:cli-sidecar","metadata":{"sourceFilename":"contract.pdf","pageCount":1}},"body":{"pages":[{"pageNumber":1,"width":1000,"height":1000,"textLayerAvailable":true,"imageHash":"sha256:image"}],"units":[{"unitId":"unit-0001","kind":"TEXT_BLOCK","page":1,"text":"Parsed by CLI sidecar.","evidenceSpanIds":["span-0001"],"location":{"page":1,"readingOrder":1},"sourceObjectId":"section-0001","confidence":{"score":1.0,"rationale":"sidecar"},"warnings":[]}],"tables":[]},"parserRun":{"parserVersion":"runtime-test","preset":"lite","backend":"sidecar","models":[],"warnings":[]},"auditGradeStatus":"AUDIT_GRADE"}
                JSON
                """, StandardCharsets.UTF_8);
        assertThat(runtime.toFile().setExecutable(true)).isTrue();
        return runtime;
    }

    private Path fakeOcrWorker(String stdout) throws IOException {
        Path worker = tempDir.resolve("fake-ocr-worker");
        Files.writeString(
                worker,
                "#!/usr/bin/env bash\n"
                        + "set -euo pipefail\n"
                        + "python3 - <<'PY'\n"
                        + "import sys\n"
                        + "sys.stdin.read()\n"
                        + "print(" + pythonLiteral(stdout) + ")\n"
                        + "PY\n",
                StandardCharsets.UTF_8);
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        return worker;
    }

    private Path fakeOcrRuntime(Path worker, double confidence, String text) throws IOException {
        Path runtime = tempDir.resolve("fake-ocr-runtime-" + Math.round(confidence * 100));
        String warning = confidence < 0.85
                ? "{\"code\":\"ocr_low_confidence\",\"severity\":\"SEVERE\",\"message\":\"OCR confidence below audit threshold\"}"
                : "";
        Files.writeString(
                runtime,
                """
                #!/usr/bin/env sh
                cat >/dev/null
                test "$DOCTRUTH_RUNTIME_MODEL_COMMAND" = "%s"
                cat <<'JSON'
                {"docId":"sha256:rust-ocr","source":{"sourceFilename":"runtime.pdf","sourceHash":"sha256:rust-ocr","metadata":{"sourceFilename":"runtime.pdf","pageCount":1}},"body":{"pages":[{"pageNumber":1,"width":1000,"height":1000,"textLayerAvailable":false,"imageHash":"sha256:image"}],"units":[{"unitId":"unit-0001","kind":"OCR_REGION","page":1,"text":"%s","evidenceSpanIds":["span-0001"],"location":{"page":1,"readingOrder":1,"boundingBox":{"x0":10,"y0":20,"x1":200,"y1":80}},"sourceObjectId":"ocr-0001","confidence":{"score":%s,"rationale":"OCR page confidence"},"warnings":[%s]}],"tables":[]},"parserRun":{"parserVersion":"runtime-test","preset":"ocr","backend":"rust-sidecar+model-worker","models":["ocr-router:v1"],"warnings":[]},"auditGradeStatus":"%s"}
                JSON
                """.formatted(
                                worker.toString(),
                                text,
                                Double.toString(confidence),
                                warning,
                                confidence < 0.85 ? "NOT_AUDIT_GRADE" : "AUDIT_GRADE"),
                StandardCharsets.UTF_8);
        assertThat(runtime.toFile().setExecutable(true)).isTrue();
        return runtime;
    }

    private static String pythonLiteral(String value) {
        return "'''" + value.replace("\\", "\\\\").replace("'''", "'\"'\"'") + "'''";
    }

    private static void withSystemProperty(String key, String value, ThrowingRunnable runnable) throws Exception {
        String previous = System.getProperty(key);
        System.setProperty(key, value);
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    private static void withSystemProperties(Map<String, String> properties, ThrowingRunnable runnable)
            throws Exception {
        var previous = new java.util.LinkedHashMap<String, String>();
        properties.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        try {
            runnable.run();
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
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
