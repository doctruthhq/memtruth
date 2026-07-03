package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for developer-facing v1 parser API entrypoints. */
class TrustDocumentParserApiContractTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("parses PDF from file path into TrustDocument with stable canonical hash")
    void parsesFromFilePathWithStableCanonicalHash() throws Exception {
        Path pdf = writePdf("Path parser smoke.");

        var first = TrustDocumentParser.parse(pdf);
        var second = TrustDocumentParser.parse(pdf);

        assertThat(first.source().sourceFilename()).isEqualTo(pdf.getFileName().toString());
        assertThat(first.source().sourceHash()).startsWith("sha256:");
        assertThat(first.parserRun().backend()).isEqualTo("rust-sidecar");
        assertThat(first.toMarkdownClean()).contains("Path parser smoke.");
        assertThat(first.canonicalHash()).isEqualTo(second.canonicalHash()).startsWith("sha256:");
    }

    @Test
    @DisplayName("explicit model-assisted preset records unavailable models instead of silent heuristic success")
    void explicitModelPresetRecordsUnavailableModelFallback() throws Exception {
        Path pdf = writePdf("Strict parser preset smoke.");

        var doc = TrustDocumentParser.parse(pdf, ParserPreset.STANDARD);

        assertThat(doc.parserRun().preset()).isEqualTo("standard");
        assertThat(doc.parserRun().backend()).isEqualTo("rust-sidecar");
        assertThat(doc.parserRun().models()).contains("layout-rtdetr:v2", "tatr:v1");
        assertThat(doc.parserRun().warnings()).hasSize(2);
        assertThat(doc.parserRun().warnings())
                .extracting(ParserWarning::code)
                .containsOnly("model_unavailable_fallback");
        assertThat(doc.parserRun().warnings())
                .extracting(ParserWarning::severity)
                .containsOnly(ParserWarningSeverity.SEVERE);
        assertThat(doc.parserRun().warnings())
                .extracting(ParserWarning::message)
                .anySatisfy(message -> assertThat(message).contains("layout-rtdetr:v2"))
                .anySatisfy(message -> assertThat(message).contains("tatr:v1"));
        assertThat(doc.auditGradeStatus()).isEqualTo(AuditGradeStatus.NOT_AUDIT_GRADE);
        assertThat(doc.toMarkdownClean()).contains("Strict parser preset smoke.");
    }

    @Test
    @DisplayName("configured Rust runtime becomes the default parser core before PDFBox fallback")
    void configuredRustRuntimeBecomesDefaultParserCore() throws Exception {
        Path pdf = writePdf("PDFBox text that should not win.");
        Path runtime = fakeRustRuntime("Rust default parser core.");

        withSystemProperty("doctruth.runtime.command", runtime.toString(), () -> {
            var doc = TrustDocumentParser.parse(pdf);

            assertThat(doc.parserRun().backend()).isEqualTo("sidecar");
            assertThat(doc.toMarkdownClean())
                    .contains("Rust default parser core.")
                    .doesNotContain("PDFBox text that should not win.");
        });
    }

    @Test
    @DisplayName("OCR preset also prefers configured Rust runtime before Java OCR fallback")
    void ocrPresetPrefersConfiguredRustRuntimeBeforeJavaOcrFallback() throws Exception {
        Path pdf = writeBlankPdf();
        Path runtime = fakeRustRuntime("Rust OCR parser core.");
        Path ocrWorker = fakeOcrWorker("""
                {"ok":true,"engine":"mnn","text":"Java OCR fallback should not win","averageConfidence":0.91,"pages":[],"warnings":[]}
                """);

        withSystemProperties(
                Map.of("doctruth.runtime.command", runtime.toString(), "doctruth.ocr.command", ocrWorker.toString()),
                () -> {
                    var doc = TrustDocumentParser.parse(pdf, ParserPreset.OCR);

                    assertThat(doc.parserRun().backend()).isEqualTo("sidecar");
                    assertThat(doc.toMarkdownClean())
                            .contains("Rust OCR parser core.")
                            .doesNotContain("Java OCR fallback should not win");
                });
    }

    @Test
    @DisplayName("table-lite preset can use a configured local model worker")
    void tableLitePresetCanUseConfiguredLocalModelWorker() throws Exception {
        Path pdf = writePdf("Model worker table source.");
        Path worker = fakeModelWorker();
        Path runtime = fakeModelWorkerRuntime(worker, null, 0.97);

        withSystemProperties(
                Map.of("doctruth.runtime.command", runtime.toString(), "doctruth.model.command", worker.toString()),
                () -> {
                    var doc = TrustDocumentParser.parse(pdf, ParserPreset.TABLE_LITE);

                    assertThat(doc.parserRun().preset()).isEqualTo("table-lite");
                    assertThat(doc.parserRun().backend()).isEqualTo("rust-sidecar+model-worker");
                    assertThat(doc.parserRun().models()).containsExactly("slanet-plus:v1");
                    assertThat(doc.parserRun().warnings())
                            .extracting(ParserWarning::code)
                            .doesNotContain("model_unavailable_fallback");
                    assertThat(doc.auditGradeStatus()).isEqualTo(AuditGradeStatus.AUDIT_GRADE);
                    assertThat(doc.body().tables()).singleElement().satisfies(table -> {
                        assertThat(table.tableId()).isEqualTo("model-table-1");
                        assertThat(table.cells())
                                .extracting(TrustTableCell::text)
                                .containsExactly("Name", "Score", "Alex", "98");
                    });
                    assertThat(doc.body().units())
                            .filteredOn(unit -> unit.kind() == TrustUnitKind.TABLE_CELL)
                            .hasSize(4);
                });
    }

    @Test
    @DisplayName("model worker request includes local model cache verification metadata")
    void modelWorkerRequestIncludesLocalModelCacheVerificationMetadata() throws Exception {
        Path pdf = writePdf("Model worker cache source.");
        Path cache = tempDir.resolve("model-cache");
        Files.createDirectories(cache);
        Path worker = fakeModelWorker(cache);
        Path runtime = fakeModelWorkerRuntime(worker, cache, 0.97);

        withSystemProperties(
                Map.of(
                        "doctruth.runtime.command", runtime.toString(),
                        "doctruth.model.command", worker.toString(),
                        "doctruth.model.cache", cache.toString()),
                () -> {
                    var doc = TrustDocumentParser.parse(pdf, ParserPreset.TABLE_LITE);

                    assertThat(doc.parserRun().backend()).isEqualTo("rust-sidecar+model-worker");
                    assertThat(doc.auditGradeStatus()).isEqualTo(AuditGradeStatus.AUDIT_GRADE);
                });
    }

    @Test
    @DisplayName("OCR preset routes low-text PDFs through the configured local OCR worker")
    void ocrPresetRoutesLowTextPdfThroughConfiguredLocalWorker() throws Exception {
        Path pdf = writeBlankPdf();
        Path worker = fakeOcrWorker("""
                {"ok":true,"engine":"mnn","text":"OCR recovered v1 trust text","averageConfidence":0.91,"pages":[],"warnings":[]}
                """);
        Path runtime = fakeOcrRuntime(worker, 0.91, "OCR recovered v1 trust text");

        withSystemProperties(
                Map.of("doctruth.runtime.command", runtime.toString(), "doctruth.ocr.command", worker.toString()),
                () -> {
                    var doc = TrustDocumentParser.parse(pdf, ParserPreset.OCR);

                    assertThat(doc.parserRun().preset()).isEqualTo("ocr");
                    assertThat(doc.parserRun().backend()).isEqualTo("rust-sidecar+model-worker");
                    assertThat(doc.parserRun().models()).contains("ocr-router:v1");
                    assertThat(doc.parserRun().warnings())
                            .extracting(ParserWarning::code)
                            .doesNotContain("model_unavailable_fallback");
                    assertThat(doc.toMarkdownClean()).contains("OCR recovered v1 trust text");
                    assertThat(doc.body().units()).singleElement().satisfies(unit -> {
                        assertThat(unit.kind()).isEqualTo(TrustUnitKind.OCR_REGION);
                        assertThat(unit.location().boundingBox()).isPresent();
                    });
                });
    }

    @Test
    @DisplayName("OCR preset marks low-confidence recovered text as non-audit-grade evidence")
    void ocrPresetMarksLowConfidenceRecoveredTextAsNonAuditGrade() throws Exception {
        Path pdf = writeBlankPdf();
        Path worker = fakeOcrWorker("""
                {"ok":true,"engine":"mnn","text":"uncertain OCR text","averageConfidence":0.42,"pages":[],"warnings":[]}
                """);
        Path runtime = fakeOcrRuntime(worker, 0.42, "uncertain OCR text");

        withSystemProperties(
                Map.of("doctruth.runtime.command", runtime.toString(), "doctruth.ocr.command", worker.toString()),
                () -> {
                    var doc = TrustDocumentParser.parse(pdf, ParserPreset.OCR);

                    assertThat(doc.auditGradeStatus()).isEqualTo(AuditGradeStatus.NOT_AUDIT_GRADE);
                    assertThat(doc.body().units()).singleElement().satisfies(unit -> {
                        assertThat(unit.kind()).isEqualTo(TrustUnitKind.OCR_REGION);
                        assertThat(unit.evidence().confidence().score()).isEqualTo(0.42);
                        assertThat(unit.evidence().confidence().rationale()).contains("OCR");
                        assertThat(unit.evidence().warnings())
                                .extracting(ParserWarning::code)
                                .containsExactly("ocr_low_confidence");
                        assertThat(unit.evidence().warnings())
                                .extracting(ParserWarning::severity)
                                .containsExactly(ParserWarningSeverity.SEVERE);
                    });
                });
    }

    @Test
    @DisplayName("parses PDF from bytes while preserving caller supplied source filename")
    void parsesFromBytes() throws Exception {
        byte[] bytes = Files.readAllBytes(writePdf("Bytes parser smoke."));

        var doc = TrustDocumentParser.parse(bytes, "upload.pdf");

        assertThat(doc.source().sourceFilename()).isEqualTo("upload.pdf");
        assertThat(doc.source().sourceHash()).isEqualTo(doc.docId());
        assertThat(doc.toMarkdownClean()).contains("Bytes parser smoke.");
    }

    @Test
    @DisplayName("byte parser can use strict preset while preserving source filename")
    void parsesBytesWithExplicitPreset() throws Exception {
        byte[] bytes = Files.readAllBytes(writePdf("Strict bytes parser smoke."));

        var doc = TrustDocumentParser.parse(bytes, "strict-upload.pdf", ParserPreset.TABLE_LITE);

        assertThat(doc.source().sourceFilename()).isEqualTo("strict-upload.pdf");
        assertThat(doc.parserRun().preset()).isEqualTo("table-lite");
        assertThat(doc.parserRun().models()).contains("slanet-plus:v1");
        assertThat(doc.parserRun().warnings())
                .extracting(ParserWarning::severity)
                .containsOnly(ParserWarningSeverity.SEVERE);
        assertThat(doc.auditGradeStatus()).isEqualTo(AuditGradeStatus.NOT_AUDIT_GRADE);
    }

    @Test
    @DisplayName("parses PDF from streaming input without caller-managed temp files")
    void parsesFromInputStream() throws Exception {
        byte[] bytes = Files.readAllBytes(writePdf("Stream parser smoke."));

        var doc = TrustDocumentParser.parse(new ByteArrayInputStream(bytes), "stream.pdf");

        assertThat(doc.source().sourceFilename()).isEqualTo("stream.pdf");
        assertThat(doc.toJsonEvidence()).contains("Stream parser smoke.");
    }

    @Test
    @DisplayName("stream parser copies input incrementally instead of calling readAllBytes")
    void streamParserDoesNotCallReadAllBytes() throws Exception {
        byte[] bytes = Files.readAllBytes(writePdf("Incremental stream parser smoke."));

        var doc = TrustDocumentParser.parse(new NoReadAllBytesInputStream(bytes), "incremental-stream.pdf");

        assertThat(doc.source().sourceFilename()).isEqualTo("incremental-stream.pdf");
        assertThat(doc.source().sourceHash()).isEqualTo("sha256:" + sha256Hex(bytes));
        assertThat(doc.toMarkdownClean()).contains("Incremental stream parser smoke.");
        assertThat(doc.body().pages().getFirst().imageHash()).startsWith("sha256:");
    }

    @Test
    @DisplayName("file source hashing uses a streaming helper")
    void fileSourceHashUsesStreamingHelper() throws Exception {
        Path source = tempDir.resolve("large-source.pdf");
        byte[] bytes = "Path source hash smoke.\n".repeat(2048).getBytes(StandardCharsets.UTF_8);
        Files.write(source, bytes);

        assertThat(TrustDocumentParser.sha256SourceFile(source)).isEqualTo("sha256:" + sha256Hex(bytes));
    }

    @Test
    @DisplayName("source hashing reports a parser error when the file cannot be opened")
    void fileSourceHashReportsUnreadableSources() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("not-a-file.pdf"));

        assertThatThrownBy(() -> TrustDocumentParser.sha256SourceFile(directory))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("failed to hash source document")
                .satisfies(error ->
                        assertThat(((ParseException) error).errorCode()).isEqualTo("SOURCE_HASH_FAILED"));
    }

    @Test
    @DisplayName("stream parser wraps input read failures as parser errors")
    void streamParserWrapsInputReadFailures() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("synthetic stream failure");
            }
        };

        assertThatThrownBy(() -> TrustDocumentParser.parse(broken, "broken-stream.pdf"))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("synthetic stream failure")
                .satisfies(error ->
                        assertThat(((ParseException) error).errorCode()).isEqualTo("PDF_STREAM_READ_FAILED"));
    }

    @Test
    @DisplayName("parseBatch preserves input order and emits one TrustDocument per source")
    void parseBatchPreservesOrder() throws Exception {
        Path first = writePdf("First batch document.");
        Path second = writePdf("Second batch document.");

        var docs = TrustDocumentParser.parseBatch(List.of(first, second));

        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).toMarkdownClean()).contains("First batch document.");
        assertThat(docs.get(1).toMarkdownClean()).contains("Second batch document.");
    }

    @Test
    @DisplayName("rejects invalid parser inputs before starting the runtime")
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> TrustDocumentParser.parse((byte[]) null, "upload.pdf"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bytes");
        assertThatThrownBy(() -> TrustDocumentParser.parse(new byte[] {1, 2, 3}, "upload.pdf", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("preset");
        assertThatThrownBy(() -> TrustDocumentParser.parse(new byte[] {1, 2, 3}, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceFilename");
        assertThatThrownBy(() -> TrustDocumentParser.parse((InputStream) null, "stream.pdf"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("input");
        assertThatThrownBy(() -> TrustDocumentParser.parseBatch(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("paths");
        assertThatThrownBy(() -> TrustDocumentParser.parseBatch(List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("preset");
        assertThatThrownBy(() -> TrustDocumentParser.parseBatch(java.util.Collections.singletonList(null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("paths[0]");
    }

    @Test
    @DisplayName("static parser cannot be instantiated")
    void staticParserCannotBeInstantiated() throws Exception {
        var constructor = TrustDocumentParser.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(AssertionError.class)
                .satisfies(error -> assertThat(error.getCause()).hasMessage("no instances"));
    }

    private Path writePdf(String text) throws Exception {
        Path path = tempDir.resolve(text.toLowerCase().replaceAll("[^a-z]+", "-") + ".pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path writeBlankPdf() throws Exception {
        Path path = tempDir.resolve("blank-ocr.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(path.toFile());
        }
        return path;
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

    private Path fakeRustRuntime(String text) throws IOException {
        Path runtime = tempDir.resolve("fake-doctruth-runtime");
        Files.writeString(runtime, """
                #!/usr/bin/env sh
                cat >/dev/null
                cat <<'JSON'
                {"docId":"sha256:rust-default","source":{"sourceFilename":"runtime.pdf","sourceHash":"sha256:rust-default","metadata":{"sourceFilename":"runtime.pdf","pageCount":1}},"body":{"pages":[{"pageNumber":1,"width":1000,"height":1000,"textLayerAvailable":true,"imageHash":"sha256:image"}],"units":[{"unitId":"unit-0001","kind":"LINE_SPAN","page":1,"text":"%s","evidenceSpanIds":["span-0001"],"location":{"page":1,"readingOrder":1},"sourceObjectId":"runtime-line-1","confidence":{"score":1.0,"rationale":"rust runtime"},"warnings":[]}],"tables":[]},"parserRun":{"parserVersion":"runtime-test","preset":"lite","backend":"sidecar","models":[],"warnings":[]},"auditGradeStatus":"AUDIT_GRADE"}
                JSON
                """.formatted(text));
        assertThat(runtime.toFile().setExecutable(true)).isTrue();
        return runtime;
    }

    private Path fakeOcrRuntime(Path worker, double confidence, String text) throws IOException {
        Path runtime = tempDir.resolve("fake-ocr-runtime-" + Math.round(confidence * 100));
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
                                confidence < 0.85
                                        ? "{\"code\":\"ocr_low_confidence\",\"severity\":\"SEVERE\",\"message\":\"OCR confidence below audit threshold\"}"
                                        : "",
                                confidence < 0.85 ? "NOT_AUDIT_GRADE" : "AUDIT_GRADE"),
                StandardCharsets.UTF_8);
        assertThat(runtime.toFile().setExecutable(true)).isTrue();
        return runtime;
    }

    private Path fakeModelWorkerRuntime(Path worker, Path expectedCache, double confidence) throws IOException {
        Path runtime = tempDir.resolve("fake-model-runtime" + (expectedCache == null ? "" : "-cache"));
        String cacheCheck = expectedCache == null ? "" : "\ntest \"$DOCTRUTH_MODEL_CACHE\" = \"" + expectedCache + "\"";
        Files.writeString(
                runtime,
                """
                #!/usr/bin/env sh
                cat >/dev/null
                test "$DOCTRUTH_RUNTIME_MODEL_COMMAND" = "%s"%s
                cat <<'JSON'
                {"docId":"sha256:rust-model","source":{"sourceFilename":"runtime.pdf","sourceHash":"sha256:rust-model","metadata":{"sourceFilename":"runtime.pdf","pageCount":1}},"body":{"pages":[{"pageNumber":1,"width":612,"height":792,"textLayerAvailable":true,"imageHash":"sha256:model-page"}],"units":[{"unitId":"unit-1","kind":"TABLE_CELL","page":1,"text":"Name","evidenceSpanIds":["unit-1-span"],"location":{"page":1,"readingOrder":11,"boundingBox":{"x0":100,"y0":100,"x1":220,"y1":150}},"sourceObjectId":"model-table-1","confidence":{"score":%s,"rationale":"fake model worker"},"warnings":[]},{"unitId":"unit-2","kind":"TABLE_CELL","page":1,"text":"Score","evidenceSpanIds":["unit-2-span"],"location":{"page":1,"readingOrder":12,"boundingBox":{"x0":220,"y0":100,"x1":340,"y1":150}},"sourceObjectId":"model-table-1","confidence":{"score":%s,"rationale":"fake model worker"},"warnings":[]},{"unitId":"unit-3","kind":"TABLE_CELL","page":1,"text":"Alex","evidenceSpanIds":["unit-3-span"],"location":{"page":1,"readingOrder":21,"boundingBox":{"x0":100,"y0":150,"x1":220,"y1":200}},"sourceObjectId":"model-table-1","confidence":{"score":%s,"rationale":"fake model worker"},"warnings":[]},{"unitId":"unit-4","kind":"TABLE_CELL","page":1,"text":"98","evidenceSpanIds":["unit-4-span"],"location":{"page":1,"readingOrder":22,"boundingBox":{"x0":220,"y0":150,"x1":340,"y1":200}},"sourceObjectId":"model-table-1","confidence":{"score":%s,"rationale":"fake model worker"},"warnings":[]}],"tables":[{"tableId":"model-table-1","pageNumber":1,"boundingBox":{"x0":100,"y0":100,"x1":340,"y1":200},"confidence":{"score":%s,"rationale":"fake model worker"},"cells":[{"cellId":"cell-1","rowRange":{"start":1,"end":1},"columnRange":{"start":1,"end":1},"boundingBox":{"x0":100,"y0":100,"x1":220,"y1":150},"text":"Name"},{"cellId":"cell-2","rowRange":{"start":1,"end":1},"columnRange":{"start":2,"end":2},"boundingBox":{"x0":220,"y0":100,"x1":340,"y1":150},"text":"Score"},{"cellId":"cell-3","rowRange":{"start":2,"end":2},"columnRange":{"start":1,"end":1},"boundingBox":{"x0":100,"y0":150,"x1":220,"y1":200},"text":"Alex"},{"cellId":"cell-4","rowRange":{"start":2,"end":2},"columnRange":{"start":2,"end":2},"boundingBox":{"x0":220,"y0":150,"x1":340,"y1":200},"text":"98"}]}]},"parserRun":{"parserVersion":"runtime-test","preset":"table-lite","backend":"rust-sidecar+model-worker","models":["slanet-plus:v1"],"warnings":[]},"auditGradeStatus":"AUDIT_GRADE"}
                JSON
                """.formatted(
                                worker.toString(),
                                cacheCheck,
                                Double.toString(confidence),
                                Double.toString(confidence),
                                Double.toString(confidence),
                                Double.toString(confidence),
                                Double.toString(confidence)),
                StandardCharsets.UTF_8);
        assertThat(runtime.toFile().setExecutable(true)).isTrue();
        return runtime;
    }

    private Path fakeModelWorker() throws IOException {
        return fakeModelWorker(null);
    }

    private Path fakeModelWorker(Path expectedCache) throws IOException {
        Path worker = tempDir.resolve("fake-model-worker");
        String cacheAssertions = expectedCache == null
                ? ""
                : """
                assert pathlib.Path(request["modelCacheDirectory"]).resolve() == pathlib.Path(%s).resolve()
                assert request["models"][0]["cachePath"].endswith("slanet-plus-v1.bin")
                assert pathlib.Path(request["models"][0]["cachePath"]).parent.resolve() == pathlib.Path(%s).resolve()
                assert request["models"][0]["cacheStatus"] == "MISSING"
                assert request["models"][0]["actualSha256"] == ""
                """.formatted(pythonLiteral(expectedCache.toString()), pythonLiteral(expectedCache.toString()));
        Files.writeString(worker, """
                #!/usr/bin/env python3
                import json
                import pathlib
                import sys

                request = json.loads(sys.stdin.read())
                assert request["preset"] == "table-lite"
                """ + cacheAssertions + """
                source = pathlib.Path(request["sourcePath"]).name

                def bbox(x0, y0, x1, y1):
                    return {"x0": x0, "y0": y0, "x1": x1, "y1": y1}

                def confidence():
                    return {"score": 0.97, "rationale": "fake model worker"}

                def table_cell(unit_id, text, row, col, x0, y0, x1, y1):
                    return {
                        "unitId": unit_id,
                        "kind": "TABLE_CELL",
                        "page": 1,
                        "text": text,
                        "evidenceSpanIds": [unit_id + "-span"],
                        "location": {"page": 1, "readingOrder": row * 10 + col, "boundingBox": bbox(x0, y0, x1, y1)},
                        "sourceObjectId": "model-table-1",
                        "confidence": confidence(),
                        "warnings": [],
                    }

                def cell(cell_id, text, row, col, x0, y0, x1, y1):
                    return {
                        "cellId": cell_id,
                        "rowRange": {"start": row, "end": row},
                        "columnRange": {"start": col, "end": col},
                        "boundingBox": bbox(x0, y0, x1, y1),
                        "text": text,
                    }

                payload = {
                    "ok": True,
                    "document": {
                        "docId": request["sourceHash"],
                        "source": {
                            "sourceFilename": source,
                            "sourceHash": request["sourceHash"],
                            "metadata": {"sourceFilename": source, "pageCount": 1},
                        },
                        "body": {
                            "pages": [{
                                "pageNumber": 1,
                                "width": 612,
                                "height": 792,
                                "textLayerAvailable": True,
                                "imageHash": "sha256:model-page"
                            }],
                            "units": [
                                table_cell("unit-1", "Name", 1, 1, 100, 100, 220, 150),
                                table_cell("unit-2", "Score", 1, 2, 220, 100, 340, 150),
                                table_cell("unit-3", "Alex", 2, 1, 100, 150, 220, 200),
                                table_cell("unit-4", "98", 2, 2, 220, 150, 340, 200),
                            ],
                            "tables": [{
                                "tableId": "model-table-1",
                                "pageNumber": 1,
                                "boundingBox": {"x0": 100, "y0": 100, "x1": 340, "y1": 200},
                                "confidence": {"score": 0.97, "rationale": "fake model worker"},
                                "cells": [
                                    cell("cell-1", "Name", 1, 1, 100, 100, 220, 150),
                                    cell("cell-2", "Score", 1, 2, 220, 100, 340, 150),
                                    cell("cell-3", "Alex", 2, 1, 100, 150, 220, 200),
                                    cell("cell-4", "98", 2, 2, 220, 150, 340, 200),
                                ],
                            }],
                        },
                        "parserRun": {
                            "parserVersion": "1.0.0",
                            "preset": "table-lite",
                            "backend": "pdfbox+model-worker",
                            "models": ["slanet-plus:v1"],
                            "warnings": [],
                        },
                        "auditGradeStatus": "UNKNOWN",
                    }
                }
                print(json.dumps(payload))
                """, StandardCharsets.UTF_8);
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        return worker;
    }

    private static String pythonLiteral(String value) {
        return "'''" + value.replace("\\", "\\\\").replace("'''", "'\"'\"'") + "'''";
    }

    private static void withSystemProperty(String key, String value, ThrowingRunnable runnable) throws Exception {
        withSystemProperties(Map.of(key, value), runnable);
    }

    private static void withSystemProperties(Map<String, String> values, ThrowingRunnable runnable) throws Exception {
        var previous = new java.util.HashMap<String, String>();
        values.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        try {
            runnable.run();
        } finally {
            values.keySet().forEach(key -> {
                String old = previous.get(key);
                if (old == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, old);
                }
            });
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class NoReadAllBytesInputStream extends InputStream {

        private final ByteArrayInputStream delegate;

        private NoReadAllBytesInputStream(byte[] bytes) {
            this.delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            throw new IOException("readAllBytes must not be used");
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
