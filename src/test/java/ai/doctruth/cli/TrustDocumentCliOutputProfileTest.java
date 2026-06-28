package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI contracts for PRD v1 TrustDocument output profiles. */
class TrustDocumentCliOutputProfileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void parseFormatJsonProfileFullWritesTrustDocumentJson() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("trust-document.json");
        var cli = cli();

        int code = cli.run(
                new String[] {"parse", pdf.toString(), "--format", "json", "--profile", "full", "--out", out.toString()
                });

        assertThat(code).isZero();
        var tree = MAPPER.readTree(Files.readString(out));
        assertThat(tree.path("docId").asText()).isNotBlank();
        assertThat(tree.path("source").path("sourceHash").asText()).startsWith("sha256:");
        assertThat(tree.path("body").path("units")).isNotEmpty();
        assertThat(tree.path("parserRun").path("backend").asText()).isEqualTo("rust-sidecar");
        assertThat(tree.path("auditGradeStatus").asText()).isNotBlank();
    }

    @Test
    void parseMarkdownCleanWithSourceMapWritesSidecarMap() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("document.md");
        var cli = cli();

        int code = cli.run(new String[] {
            "parse",
            pdf.toString(),
            "--format",
            "markdown",
            "--profile",
            "clean",
            "--source-map",
            "--out",
            out.toString()
        });

        assertThat(code).isZero();
        String markdown = Files.readString(out);
        assertThat(markdown).contains("Acme Industrial Materials Pty Ltd").doesNotContain("span-");
        Path map = tempDir.resolve("document.doctruth-map.json");
        assertThat(Files.exists(map)).isTrue();
        var tree = MAPPER.readTree(Files.readString(map));
        assertThat(tree.path("format").asText()).isEqualTo("markdown");
        assertThat(tree.path("sourceHash").asText()).startsWith("sha256:");
        assertThat(tree.path("contentHash").asText()).isEqualTo(sha256(markdown));
        assertThat(tree.path("sourceMap")).isNotEmpty();
        assertThat(tree.path("sourceMap").get(0).path("unitId").asText()).startsWith("unit-");
    }

    @Test
    void parseCompactWithSourceMapWritesVerifiableSidecarMap() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("context.doctruth-wire");
        var parse = cli();

        int code = parse.run(
                new String[] {"parse", pdf.toString(), "--format", "compact", "--source-map", "--out", out.toString()});

        assertThat(code).isZero();
        String compact = Files.readString(out);
        assertThat(compact).startsWith("doc|").contains("span-");
        Path map = tempDir.resolve("context.doctruth-map.json");
        assertThat(Files.exists(map)).isTrue();
        var tree = MAPPER.readTree(Files.readString(map));
        assertThat(tree.path("format").asText()).isEqualTo("compact_llm");
        assertThat(tree.path("contentHash").asText()).isEqualTo(sha256(compact));
        assertThat(tree.path("sourceMap")).isNotEmpty();

        var verify = cli();
        int verifyCode = verify.run(
                new String[] {"verify-source-map", out.toString(), map.toString(), "--source", pdf.toString()});

        assertThat(verifyCode).isZero();
        assertThat(verify.out()).contains("source map verified");
    }

    @Test
    void verifySourceMapChecksRenderedContentAndSourceHash() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("document.md");
        var parse = cli();
        parse.run(new String[] {
            "parse",
            pdf.toString(),
            "--format",
            "markdown",
            "--profile",
            "clean",
            "--source-map",
            "--out",
            out.toString()
        });
        Path map = tempDir.resolve("document.doctruth-map.json");
        var verify = cli();

        int code = verify.run(
                new String[] {"verify-source-map", out.toString(), map.toString(), "--source", pdf.toString()});

        assertThat(code).isZero();
        assertThat(verify.out()).contains("source map verified");
    }

    @Test
    void verifySourceMapRejectsTamperedRenderedContent() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("document.md");
        var parse = cli();
        parse.run(new String[] {
            "parse",
            pdf.toString(),
            "--format",
            "markdown",
            "--profile",
            "clean",
            "--source-map",
            "--out",
            out.toString()
        });
        Files.writeString(out, Files.readString(out) + "\nTampered line.\n");
        Path map = tempDir.resolve("document.doctruth-map.json");
        var verify = cli();

        int code = verify.run(
                new String[] {"verify-source-map", out.toString(), map.toString(), "--source", pdf.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verify.err()).contains("content hash mismatch");
    }

    @Test
    void verifySourceMapHashesRenderedAndSourceFilesThroughStreamingHelpers() throws Exception {
        Path rendered = tempDir.resolve("large.md");
        Path source = tempDir.resolve("source.bin");
        String text = "Evidence line\n".repeat(1024);
        byte[] sourceBytes = new byte[4096];
        for (int i = 0; i < sourceBytes.length; i++) {
            sourceBytes[i] = (byte) (i % 251);
        }
        Files.writeString(rendered, text, StandardCharsets.UTF_8);
        Files.write(source, sourceBytes);

        assertThat(VerifySourceMapCommand.sha256RenderedTextFile(rendered)).isEqualTo(sha256(text));
        assertThat(VerifySourceMapCommand.sha256SourceFile(source)).isEqualTo(sha256(sourceBytes));
    }

    @Test
    void parseCommandSourceHashUsesStreamingHelper() throws Exception {
        Path source = tempDir.resolve("source-for-sidecar.bin");
        byte[] sourceBytes = "Sidecar source hash smoke.\n".repeat(2048).getBytes(StandardCharsets.UTF_8);
        Files.write(source, sourceBytes);

        assertThat(ParseCommand.sourceHashForFile(source)).isEqualTo(sha256(sourceBytes));
    }

    @Test
    void parseMarkdownAnchoredAndCompactProfilesPrintEvidenceBearingOutput() throws Exception {
        Path pdf = samplePdf();
        var anchored = cli();

        int anchoredCode =
                anchored.run(new String[] {"parse", pdf.toString(), "--format", "markdown", "--profile", "anchored"});

        assertThat(anchoredCode).isZero();
        assertThat(anchored.out()).contains("{#ev:span-").contains("page=1");

        var compact = cli();
        int compactCode = compact.run(new String[] {"parse", pdf.toString(), "--format", "compact"});

        assertThat(compactCode).isZero();
        assertThat(compact.out()).startsWith("doc|").contains("span-").contains("Acme");
    }

    @Test
    void parseJsonlAndAuditProfilesAreMachineReadable() throws Exception {
        Path pdf = samplePdf();
        var jsonl = cli();

        int jsonlCode = jsonl.run(new String[] {"parse", pdf.toString(), "--format", "jsonl"});

        assertThat(jsonlCode).isZero();
        assertThat(jsonl.out()).contains("\"type\":\"unit\"").contains("\"evidence_span_ids\"");

        var audit = cli();
        int auditCode = audit.run(new String[] {"parse", pdf.toString(), "--format", "audit"});

        assertThat(auditCode).isZero();
        var tree = MAPPER.readTree(audit.out());
        assertThat(tree.path("format").asText()).isEqualTo("doctruth.trust_document.audit.v1");
        assertThat(tree.path("sourceHash").asText()).startsWith("sha256:");
        assertThat(tree.path("canonicalHash").asText()).startsWith("sha256:");
        assertThat(tree.path("evidenceHash").asText()).startsWith("sha256:");
        assertThat(tree.path("parserRun").path("backend").asText()).isEqualTo("rust-sidecar");
    }

    @Test
    void parseContentBlocksProfileWritesFlatReadingOrderBlocks() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("contract.content_blocks.json");
        var cli = cli();

        int code =
                cli.run(new String[] {"parse", pdf.toString(), "--format", "content_blocks", "--out", out.toString()});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(Files.readString(out));
        assertThat(tree.path("format").asText()).isEqualTo("doctruth.content_blocks.v1");
        assertThat(tree.path("sourceHash").asText()).startsWith("sha256:");
        assertThat(tree.path("contentBlocks")).isNotEmpty();
        var block = tree.path("contentBlocks").get(0);
        assertThat(block.path("blockId").asText()).startsWith("block-");
        assertThat(block.path("type").asText()).isEqualTo("text");
        assertThat(block.path("text").asText()).contains("Acme Industrial Materials Pty Ltd");
        assertThat(block.path("sourceUnitIds").get(0).asText()).startsWith("unit-");
        assertThat(block.path("evidenceSpanIds").get(0).asText()).startsWith("span-");
        assertThat(block.path("bbox").isObject()).isTrue();
    }

    @Test
    void parseAdditionalTrustFormatsCanWriteToStdout() throws Exception {
        Path pdf = samplePdf();
        var contentBlocks = cli();
        var parseTrace = cli();
        var html = cli();
        var jsonEvidence = cli();

        int contentBlocksCode = contentBlocks.run(new String[] {"parse", pdf.toString(), "--format", "content_blocks"});
        int parseTraceCode = parseTrace.run(new String[] {"parse", pdf.toString(), "--format", "parse_trace"});
        int htmlCode = html.run(new String[] {"parse", pdf.toString(), "--format", "html"});
        int jsonEvidenceCode =
                jsonEvidence.run(new String[] {"parse", pdf.toString(), "--format", "json", "--profile", "evidence"});

        assertThat(contentBlocksCode).isZero();
        assertThat(contentBlocks.out()).contains("doctruth.content_blocks.v1").contains("sourceUnitIds");
        assertThat(parseTraceCode).isZero();
        assertThat(parseTrace.out()).contains("doctruth.parse_trace.v1").contains("readingBlocks");
        assertThat(htmlCode).isZero();
        assertThat(html.out())
                .contains("<article data-trust-doc-id=")
                .contains("data-trust-unit-id=\"unit-")
                .contains("Acme Industrial Materials Pty Ltd");
        assertThat(jsonEvidenceCode).isZero();
        assertThat(MAPPER.readTree(jsonEvidence.out()).path("units")).isNotEmpty();
    }

    @Test
    void parseTraceProfileWritesBlockLineSpanEvidenceLayer() throws Exception {
        Path pdf = samplePdf();
        Path out = tempDir.resolve("contract.parse_trace.json");
        var cli = cli();

        int code = cli.run(new String[] {"parse", pdf.toString(), "--format", "parse_trace", "--out", out.toString()});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(Files.readString(out));
        assertThat(tree.path("format").asText()).isEqualTo("doctruth.parse_trace.v1");
        assertThat(tree.path("sourceHash").asText()).startsWith("sha256:");
        var page = tree.path("parseTrace").path("pages").get(0);
        assertThat(page.path("pageIndex").asInt()).isZero();
        assertThat(page.path("pageSize").path("width").asDouble()).isGreaterThan(0.0);
        assertThat(page.path("pageSize").path("height").asDouble()).isGreaterThan(0.0);
        assertThat(page.path("pageSize").has("x0")).isFalse();
        assertThat(page.path("readingBlocks")).isNotEmpty();
        var block = page.path("readingBlocks").get(0);
        assertThat(block.path("blockId").asText()).startsWith("block-");
        assertThat(block.path("sourceUnitIds").get(0).asText()).startsWith("unit-");
        assertThat(block.path("evidenceSpanIds").get(0).asText()).startsWith("span-");
        var line = block.path("lines").get(0);
        assertThat(line.path("lineId").asText()).startsWith("line-");
        assertThat(line.path("spans").get(0).path("sourceObjectId").asText()).isNotBlank();
        assertThat(line.path("spans").get(0).path("evidenceSpanId").asText()).startsWith("span-");
    }

    @Test
    void verifyAuditChecksAuditPackageAgainstTrustDocumentJson() throws Exception {
        Path pdf = samplePdf();
        Path full = tempDir.resolve("trust-document.json");
        Path audit = tempDir.resolve("audit.json");
        var parseFull = cli();
        var parseAudit = cli();
        parseFull.run(
                new String[] {"parse", pdf.toString(), "--format", "json", "--profile", "full", "--out", full.toString()
                });
        parseAudit.run(new String[] {"parse", pdf.toString(), "--format", "audit", "--out", audit.toString()});
        var verify = cli();

        int code = verify.run(new String[] {"verify-audit", full.toString(), audit.toString()});

        assertThat(code).isZero();
        assertThat(verify.out()).contains("audit package verified");
    }

    @Test
    void verifyAuditRejectsTamperedAuditPackage() throws Exception {
        Path pdf = samplePdf();
        Path full = tempDir.resolve("trust-document.json");
        Path audit = tempDir.resolve("audit.json");
        var parseFull = cli();
        var parseAudit = cli();
        parseFull.run(
                new String[] {"parse", pdf.toString(), "--format", "json", "--profile", "full", "--out", full.toString()
                });
        parseAudit.run(new String[] {"parse", pdf.toString(), "--format", "audit", "--out", audit.toString()});
        Files.writeString(
                audit, Files.readString(audit).replace("Acme Industrial Materials Pty Ltd", "Tampered Pty Ltd"));
        var verify = cli();

        int code = verify.run(new String[] {"verify-audit", full.toString(), audit.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verify.err()).contains("evidence");
    }

    @Test
    void verifyAuditRejectsBadUsageAndMissingFiles() {
        var badUsage = cli();
        var missingFiles = cli();

        int badUsageCode = badUsage.run(new String[] {"verify-audit", "only-one.json"});
        int missingFilesCode = missingFiles.run(new String[] {
            "verify-audit",
            tempDir.resolve("missing-trust.json").toString(),
            tempDir.resolve("missing-audit.json").toString()
        });

        assertThat(badUsageCode).isEqualTo(2);
        assertThat(badUsage.err()).contains("usage: doctruth verify-audit");
        assertThat(missingFilesCode).isEqualTo(1);
        assertThat(missingFiles.err()).contains("failed to read audit verification inputs");
    }

    @Test
    void parsePlainTextProfilePrintsCleanTextWithoutMarkdownSyntax() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"parse", pdf.toString(), "--format", "plain"});

        assertThat(code).isZero();
        assertThat(cli.out())
                .contains("Acme Industrial Materials Pty Ltd")
                .contains("AUD 2,450,000")
                .doesNotContain("{#ev:")
                .doesNotContain("| --- |");
    }

    @Test
    void parseRejectsUnknownTrustOutputProfile() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"parse", pdf.toString(), "--format", "markdown", "--profile", "wat"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("unknown parse profile");
    }

    @Test
    void parseCanUseSidecarBackendRuntimeForTrustOutput() throws Exception {
        Path pdf = samplePdf();
        Path runtime = fakeSidecarRuntime();
        var cli = cli();

        int code = cli.run(new String[] {
            "parse",
            pdf.toString(),
            "--backend",
            "sidecar",
            "--runtime",
            runtime.toString(),
            "--preset",
            "standard",
            "--format",
            "markdown",
            "--profile",
            "clean"
        });

        assertThat(code).isZero();
        assertThat(cli.out()).contains("Parsed by CLI sidecar.").doesNotContain("Acme Industrial");
    }

    @Test
    void parseDefaultSummaryUsesConfiguredRustRuntime() throws Exception {
        Path pdf = samplePdf();
        Path runtime = fakeSidecarRuntime();
        var cli = cli(Map.of("DOCTRUTH_RUNTIME_COMMAND", runtime.toString()));

        int code = cli.run(new String[] {"parse", pdf.toString()});

        assertThat(code).isZero();
        assertThat(cli.out())
                .contains("parser backend: sidecar")
                .contains("units: 1")
                .doesNotContain("sections:");
    }

    @Test
    void parseTrustOutputRequiresRustRuntimeUnlessPdfboxIsExplicit() throws Exception {
        Path pdf = samplePdf();
        var auto = cli();

        int code = withSystemProperty(
                "doctruth.runtime.disableSourceDiscovery",
                "true",
                () -> withSystemProperty(
                        "doctruth.runtime.disableEnvironmentDiscovery",
                        "true",
                        () -> auto.run(
                                new String[] {"parse", pdf.toString(), "--backend", "auto", "--format", "json"})));

        assertThat(code).isEqualTo(1);
        assertThat(auto.err()).contains("RUST_RUNTIME_NOT_CONFIGURED").contains("Rust runtime is required");
    }

    @Test
    void parseCanUseExplicitPdfboxFallbackAndConfiguredAutoRustBackends() throws Exception {
        Path pdf = samplePdf();
        Path runtime = fakeSidecarRuntime();
        var pdfbox = cli();
        var auto = cli(Map.of("DOCTRUTH_RUNTIME_COMMAND", runtime.toString()));

        int pdfboxCode = pdfbox.run(new String[] {"parse", pdf.toString(), "--backend", "pdfbox", "--format", "json"});
        int autoCode = auto.run(new String[] {"parse", pdf.toString(), "--backend", "auto", "--format", "json"});

        assertThat(pdfboxCode).isZero();
        assertThat(MAPPER.readTree(pdfbox.out())
                        .path("parserRun")
                        .path("backend")
                        .asText())
                .isEqualTo("pdfbox");
        assertThat(autoCode).isZero();
        assertThat(MAPPER.readTree(auto.out()).path("parserRun").path("backend").asText())
                .isEqualTo("sidecar");
    }

    @Test
    void parseRejectsConflictingOutputFormats() throws Exception {
        Path pdf = samplePdf();
        var cli = cli();

        int code = cli.run(new String[] {"parse", pdf.toString(), "--json", "--format", "markdown"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("cannot be combined");
    }

    @Test
    void parseRejectsSourceMapWithoutOutOrSupportedFormat() throws Exception {
        Path pdf = samplePdf();
        var missingOut = cli();
        var unsupportedFormat = cli();

        int missingOutCode =
                missingOut.run(new String[] {"parse", pdf.toString(), "--format", "markdown", "--source-map"});
        int unsupportedCode = unsupportedFormat.run(new String[] {
            "parse",
            pdf.toString(),
            "--format",
            "json",
            "--source-map",
            "--out",
            tempDir.resolve("doc.json").toString()
        });

        assertThat(missingOutCode).isEqualTo(2);
        assertThat(missingOut.err()).contains("--source-map requires --out");
        assertThat(unsupportedCode).isEqualTo(2);
        assertThat(unsupportedFormat.err()).contains("--source-map is only supported");
    }

    @Test
    void parseRejectsProfilesForIncompatibleFormats() throws Exception {
        Path pdf = samplePdf();
        var plainEvidence = cli();
        var jsonAnchored = cli();

        int plainCode =
                plainEvidence.run(new String[] {"parse", pdf.toString(), "--format", "plain", "--profile", "evidence"});
        int jsonCode =
                jsonAnchored.run(new String[] {"parse", pdf.toString(), "--format", "json", "--profile", "anchored"});

        assertThat(plainCode).isEqualTo(2);
        assertThat(plainEvidence.err()).contains("only valid for markdown or json formats");
        assertThat(jsonCode).isEqualTo(2);
        assertThat(jsonAnchored.err()).contains("anchored is only valid for markdown");
    }

    @Test
    void parseRejectsInvalidBackendRuntimeCombinations() throws Exception {
        Path pdf = samplePdf();
        Path runtime = fakeSidecarRuntime();
        var missingRuntime = cli();
        var missingFormat = cli();
        var runtimeAsDefault = cli();
        var runtimeWithPdfbox = cli();
        var unknownBackend = cli();

        int missingRuntimeCode = withSystemProperty(
                "doctruth.runtime.disableSourceDiscovery",
                "true",
                () -> withSystemProperty(
                        "doctruth.runtime.disableEnvironmentDiscovery",
                        "true",
                        () -> missingRuntime.run(
                                new String[] {"parse", pdf.toString(), "--backend", "sidecar", "--format", "markdown"
                                })));
        int summaryCode = missingFormat.run(
                new String[] {"parse", pdf.toString(), "--backend", "sidecar", "--runtime", runtime.toString()});
        int runtimeAsDefaultCode = runtimeAsDefault.run(
                new String[] {"parse", pdf.toString(), "--runtime", runtime.toString(), "--format", "markdown"});
        int runtimeWithPdfboxCode = runtimeWithPdfbox.run(new String[] {
            "parse", pdf.toString(), "--backend", "pdfbox", "--runtime", runtime.toString(), "--format", "markdown"
        });
        int unknownBackendCode =
                unknownBackend.run(new String[] {"parse", pdf.toString(), "--backend", "wat", "--format", "markdown"});

        assertThat(missingRuntimeCode).isEqualTo(1);
        assertThat(missingRuntime.err()).contains("RUST_RUNTIME_NOT_CONFIGURED");
        assertThat(summaryCode).isZero();
        assertThat(missingFormat.out()).contains("parser backend: sidecar");
        assertThat(runtimeAsDefaultCode).isZero();
        assertThat(runtimeAsDefault.out()).contains("Parsed by CLI sidecar.").doesNotContain("Acme Industrial");
        assertThat(runtimeWithPdfboxCode).isEqualTo(2);
        assertThat(runtimeWithPdfbox.err()).contains("--runtime cannot be combined with --backend pdfbox");
        assertThat(unknownBackendCode).isEqualTo(2);
        assertThat(unknownBackend.err()).contains("unknown parser backend");
    }

    private TestCli cli() {
        return cli(Map.of());
    }

    private TestCli cli(Map<String, String> env) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var cli = new DocTruthCli(
                env,
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
                cs.newLineAtOffset(0, -18);
                cs.showText("Total Value: AUD 2,450,000");
                cs.endText();
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path fakeSidecarRuntime() throws IOException {
        Path runtime = tempDir.resolve("fake-doctruth-runtime");
        Files.writeString(runtime, """
                #!/usr/bin/env sh
                REQ=$(cat)
                case "$REQ" in
                  *'"backend"'*) echo 'backend should not be sent by CLI' >&2; exit 9 ;;
                  *'"preset":"standard"'*|*'"preset":"lite"'*) ;;
                  *) echo "unexpected request: $REQ" >&2; exit 7 ;;
                esac
                cat <<'JSON'
                {"docId":"sha256:cli-sidecar","source":{"sourceFilename":"contract.pdf","sourceHash":"sha256:cli-sidecar","metadata":{"sourceFilename":"contract.pdf","pageCount":1}},"body":{"pages":[{"pageNumber":1,"width":1000,"height":1000,"textLayerAvailable":true,"imageHash":"sha256:image"}],"units":[{"unitId":"unit-0001","kind":"TEXT_BLOCK","page":1,"text":"Parsed by CLI sidecar.","evidenceSpanIds":["span-0001"],"location":{"page":1,"readingOrder":1},"sourceObjectId":"section-0001","confidence":{"score":1.0,"rationale":"sidecar"},"warnings":[]}],"tables":[]},"parserRun":{"parserVersion":"runtime-test","preset":"standard","backend":"sidecar","models":[],"warnings":[]},"auditGradeStatus":"AUDIT_GRADE"}
                JSON
                """);
        runtime.toFile().setExecutable(true);
        return runtime;
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

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static int withSystemProperty(String key, String value, IntSupplier supplier) {
        var previous = System.getProperty(key);
        System.setProperty(key, value);
        try {
            return supplier.getAsInt();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @FunctionalInterface
    private interface IntSupplier {
        int getAsInt();
    }
}
