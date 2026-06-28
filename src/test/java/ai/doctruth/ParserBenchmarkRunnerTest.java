package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for lightweight parser quality metric evaluation. */
class ParserBenchmarkRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("benchmark runner reports perfect reading-order and quote metrics for exact output")
    void exactDocumentScoresPerfectly() {
        var doc = document("Work Experience\nJava Engineer\nEducation\nComputer Science");

        var result = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                        "single-column", doc, "Work Experience\nJava Engineer\nEducation\nComputer Science")))
                .getFirst();

        assertThat(result.name()).isEqualTo("single-column");
        assertThat(result.metric("reading_order_f1")).isEqualTo(1.0);
        assertThat(result.metric("quote_anchor_accuracy")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("benchmark runner lowers reading-order score when expected text order is not preserved")
    void reorderedDocumentLosesReadingOrderScore() {
        var doc = document("Education\nComputer Science\nWork Experience\nJava Engineer");

        var result = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                        "two-column", doc, "Work Experience\nJava Engineer\nEducation\nComputer Science")))
                .getFirst();

        assertThat(result.metric("reading_order_f1")).isLessThan(1.0);
        assertThat(result.metric("quote_anchor_accuracy")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("benchmark runner reports section boundary F1 for recovered heading lines")
    void benchmarkReportsSectionBoundaryF1() {
        var doc = document("PROFILE\nExperienced operator\nWORK EXPERIENCE\nProduction assistant");

        var result = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                        "section-boundaries",
                        doc,
                        "PROFILE\nExperienced operator\nWORK EXPERIENCE\nProduction assistant")))
                .getFirst();

        assertThat(result.metric("section_boundary_f1")).isEqualTo(1.0);
        ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("section_boundary_f1", 1.0));
    }

    @Test
    @DisplayName("benchmark runner lowers section boundary F1 when headings are merged into body text")
    void benchmarkLowersSectionBoundaryF1ForMergedHeadingText() {
        var doc = document("PROFILE Experienced operator\nWORK EXPERIENCE\nProduction assistant");

        var result = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                        "merged-section-boundary",
                        doc,
                        "PROFILE\nExperienced operator\nWORK EXPERIENCE\nProduction assistant")))
                .getFirst();

        assertThat(result.metric("section_boundary_f1")).isLessThan(1.0);
        assertThatThrownBy(() ->
                        ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("section_boundary_f1", 0.90)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("merged-section-boundary")
                .hasMessageContaining("section_boundary_f1");
    }

    @Test
    @DisplayName("benchmark runner enforces acceptance thresholds with case and metric context")
    void thresholdGateFailsBelowMinimum() {
        var doc = document("Education\nComputer Science\nWork Experience\nJava Engineer");
        var results = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                "two-column", doc, "Work Experience\nJava Engineer\nEducation\nComputer Science")));

        assertThatThrownBy(() -> ParserBenchmarkRunner.requireMinimums(
                        results, Map.of("reading_order_f1", 0.95, "quote_anchor_accuracy", 1.0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("two-column")
                .hasMessageContaining("reading_order_f1")
                .hasMessageContaining("0.95");
    }

    @Test
    @DisplayName("benchmark runner accepts results that meet configured thresholds")
    void thresholdGatePassesAtMinimum() {
        var doc = document("Work Experience\nJava Engineer\nEducation\nComputer Science");
        var results = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                "single-column", doc, "Work Experience\nJava Engineer\nEducation\nComputer Science")));

        ParserBenchmarkRunner.requireMinimums(results, Map.of("reading_order_f1", 1.0, "quote_anchor_accuracy", 1.0));
    }

    @Test
    @DisplayName("real-PDF benchmark factory rejects missing source paths")
    void realPdfBenchmarkFactoryRejectsMissingSourcePath() {
        assertThatThrownBy(() -> ParserBenchmarkCase.fromPdf("missing-source", null, "Expected"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sourcePath");
    }

    @Test
    @DisplayName("benchmark runner reports compact LLM size reduction and replay health")
    void benchmarkReportsCompactLlmCorpusMetrics() {
        var doc = document("Work Experience\nJava Engineer\nEducation\nComputer Science");

        var result = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                        "compact-replay", doc, "Work Experience\nJava Engineer\nEducation\nComputer Science")))
                .getFirst();

        assertThat(result.metric("compact_llm_size_reduction")).isGreaterThanOrEqualTo(0.25);
        assertThat(result.metric("compact_llm_round_trip")).isEqualTo(1.0);
        assertThat(result.metric("compact_llm_source_map_coverage")).isEqualTo(1.0);
        ParserBenchmarkRunner.requireMinimums(
                List.of(result),
                Map.of(
                        "compact_llm_size_reduction", 0.25,
                        "compact_llm_round_trip", 1.0,
                        "compact_llm_source_map_coverage", 1.0));
    }

    @Test
    @DisplayName("benchmark runner reports OCR text accuracy against expected text")
    void benchmarkReportsOcrTextAccuracy() {
        var doc = ocrDocument("Invoice Total 123");

        var result = ParserBenchmarkRunner.evaluate(
                        List.of(new ParserBenchmarkCase("ocr-smoke", doc, "Invoice Total 123")))
                .getFirst();

        assertThat(result.metric("ocr_text_accuracy")).isEqualTo(1.0);
        ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("ocr_text_accuracy", 1.0));
    }

    @Test
    @DisplayName("benchmark runner lowers OCR text accuracy when OCR text misses expected content")
    void benchmarkLowersOcrTextAccuracyForMissingText() {
        var doc = ocrDocument("Invoice 123");

        var result = ParserBenchmarkRunner.evaluate(
                        List.of(new ParserBenchmarkCase("ocr-missing-token", doc, "Invoice Total 123")))
                .getFirst();

        assertThat(result.metric("ocr_text_accuracy")).isLessThan(1.0);
        assertThatThrownBy(
                        () -> ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("ocr_text_accuracy", 0.95)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ocr-missing-token")
                .hasMessageContaining("ocr_text_accuracy");
    }

    @Test
    @DisplayName("benchmark size metrics use writer-backed byte counters")
    void benchmarkSizeMetricsUseWriterBackedByteCounters() {
        var doc = document("Work Experience\nJava Engineer\nEducation\nComputer Science");

        assertThat(ParserBenchmarkRunner.jsonFullByteLength(doc))
                .isEqualTo(doc.toJsonFull().getBytes(StandardCharsets.UTF_8).length);
        assertThat(ParserBenchmarkRunner.compactLlmByteLength(doc))
                .isEqualTo(doc.toCompactLlm().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("benchmark runner reports parser latency for each parsed case")
    void benchmarkReportsParserLatencyForEachCase() {
        var doc = document("Work Experience\nJava Engineer");

        var result = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                        "latency-case", doc, "Work Experience\nJava Engineer", Optional.empty(), 123.5)))
                .getFirst();

        assertThat(result.metric("parser_latency_ms")).isEqualTo(123.5);
    }

    @Test
    @DisplayName("benchmark runner aggregates parser latency p50 and p95")
    void benchmarkAggregatesParserLatencyPercentiles() {
        var doc = document("Work Experience\nJava Engineer");
        var results = ParserBenchmarkRunner.evaluate(List.of(
                new ParserBenchmarkCase("latency-1", doc, "Work Experience\nJava Engineer", Optional.empty(), 100.0),
                new ParserBenchmarkCase("latency-2", doc, "Work Experience\nJava Engineer", Optional.empty(), 200.0),
                new ParserBenchmarkCase("latency-3", doc, "Work Experience\nJava Engineer", Optional.empty(), 300.0)));

        var aggregate = ParserBenchmarkRunner.aggregateMetrics(results);

        assertThat(aggregate).containsEntry("parser_latency_p50", 200.0);
        assertThat(aggregate).containsEntry("parser_latency_p95", 300.0);
    }

    @Test
    @DisplayName("benchmark runner aggregates compact LLM reduction as a corpus minimum")
    void benchmarkAggregatesCompactLlmReductionMinimum() {
        var results = List.of(
                new ParserBenchmarkResult("compact-a", Map.of("compact_llm_size_reduction", 0.31)),
                new ParserBenchmarkResult("compact-b", Map.of("compact_llm_size_reduction", 0.27)));

        var aggregate = ParserBenchmarkRunner.aggregateMetrics(results);

        assertThat(aggregate).containsEntry("compact_llm_size_reduction_min", 0.27);
    }

    @Test
    @DisplayName("benchmark runner reports runtime memory and model cache resource metrics")
    void benchmarkReportsResourceMetrics() {
        var doc = document("Work Experience\nJava Engineer");

        var result = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                        "resource-case", doc, "Work Experience\nJava Engineer", Optional.empty(), 123.0, 256.5, 30.25)))
                .getFirst();

        assertThat(result.metric("rss_peak_mb")).isEqualTo(256.5);
        assertThat(result.metric("model_cache_size_mb")).isEqualTo(30.25);
        ParserBenchmarkRunner.requireMaximums(
                List.of(result), Map.of("rss_peak_mb", 512.0, "model_cache_size_mb", 64.0));
    }

    @Test
    @DisplayName("real-PDF benchmark factory records configured model cache size")
    void realPdfBenchmarkFactoryRecordsConfiguredModelCacheSize() throws Exception {
        Path cache = tempDir.resolve("model-cache");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve("layout.onnx"), "model-bytes");
        Path pdf = writePositionedPdf(List.of(run("Work Experience", 72f, 720f), run("Java Engineer", 72f, 700f)));

        ParserBenchmarkCase benchmarkCase = withSystemProperty(
                "doctruth.model.cache",
                cache.toString(),
                () -> ParserBenchmarkCase.fromPdf("cached-model-case", pdf, "Work Experience\nJava Engineer\n"));

        assertThat(benchmarkCase.modelCacheSizeMb()).isGreaterThan(0.0);
        assertThat(benchmarkCase.rssPeakMb()).isGreaterThanOrEqualTo(0.0);
        assertThat(benchmarkCase.parserLatencyMs()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("benchmark runner reports bbox IoU and table-cell F1 against expected TrustDocument")
    void benchmarkUsesExpectedTrustDocumentForLayoutMetrics() {
        var expected = documentWithTable("Expected", new BoundingBox(100, 100, 300, 180));
        var actual = documentWithTable("Actual", new BoundingBox(100, 100, 300, 180));

        var result = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                        "bordered-table", actual, actual.toMarkdownClean(), Optional.of(expected))))
                .getFirst();

        assertThat(result.metric("bbox_iou")).isEqualTo(1.0);
        assertThat(result.metric("table_cell_f1")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("benchmark runner reports evidence span accuracy against expected TrustDocument labels")
    void benchmarkReportsEvidenceSpanAccuracy() {
        var expected = documentWithEvidenceSpan("expected-evidence", "Profile summary", List.of("span-profile"));
        var actual = documentWithEvidenceSpan("actual-evidence", "Profile summary", List.of("span-profile"));

        var result = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                        "evidence-span", actual, actual.toMarkdownClean(), Optional.of(expected))))
                .getFirst();

        assertThat(result.metric("evidence_span_accuracy")).isEqualTo(1.0);
        ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("evidence_span_accuracy", 1.0));
    }

    @Test
    @DisplayName("benchmark runner lowers evidence span accuracy when matching text has no evidence span")
    void benchmarkLowersEvidenceSpanAccuracyForMissingSpan() {
        var expected = documentWithEvidenceSpan("expected-evidence", "Profile summary", List.of("span-profile"));
        var actual = documentWithEvidenceSpan("actual-evidence", "Profile summary", List.of());

        var result = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                        "missing-evidence-span", actual, actual.toMarkdownClean(), Optional.of(expected))))
                .getFirst();

        assertThat(result.metric("evidence_span_accuracy")).isLessThan(1.0);
        assertThatThrownBy(() ->
                        ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("evidence_span_accuracy", 0.97)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-evidence-span")
                .hasMessageContaining("evidence_span_accuracy");
    }

    @Test
    @DisplayName("benchmark runner reports strict parser warning false negatives")
    void benchmarkReportsStrictWarningFalseNegativeRate() {
        var expected = documentWithParserWarnings(List.of(severe("layout_low_confidence")));
        var actual = documentWithParserWarnings(List.of());

        var result = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                        "missing-strict-warning", actual, actual.toMarkdownClean(), Optional.of(expected))))
                .getFirst();

        assertThat(result.metric("strict_warning_false_negative_rate")).isEqualTo(1.0);
        assertThatThrownBy(() -> ParserBenchmarkRunner.requireMaximums(
                        List.of(result), Map.of("strict_warning_false_negative_rate", 0.02)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-strict-warning")
                .hasMessageContaining("strict_warning_false_negative_rate");
    }

    @Test
    @DisplayName("benchmark warning metric matches parserRun and unit-local severe warnings")
    void benchmarkStrictWarningMetricMatchesParserAndUnitWarnings() {
        var expected =
                documentWithWarnings(List.of(severe("layout_low_confidence")), List.of(severe("ocr_low_confidence")));
        var actual =
                documentWithWarnings(List.of(severe("layout_low_confidence")), List.of(severe("ocr_low_confidence")));

        var result = ParserBenchmarkRunner.evaluate(List.of(new ParserBenchmarkCase(
                        "matched-strict-warning", actual, actual.toMarkdownClean(), Optional.of(expected))))
                .getFirst();

        assertThat(result.metric("strict_warning_false_negative_rate")).isEqualTo(0.0);
        ParserBenchmarkRunner.requireMaximums(List.of(result), Map.of("strict_warning_false_negative_rate", 0.02));
    }

    @Test
    @DisplayName("benchmark case can parse a real PDF fixture and gate reading order plus bbox coverage")
    void benchmarkCanParseRealPdfFixture() throws Exception {
        var pdf = writePositionedPdf(List.of(
                run("CONTACT", 50f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("+6011-19822183", 50f, 700f),
                run("PROFILE", 320f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("Experienced business development executive.", 320f, 700f)));

        var benchmark = ParserBenchmarkCase.fromPdf("two-column-real-pdf", pdf, """
                CONTACT
                +6011-19822183
                PROFILE
                Experienced business development executive.
                """);
        var result = ParserBenchmarkRunner.evaluate(List.of(benchmark)).getFirst();

        assertThat(result.metric("reading_order_f1")).isEqualTo(1.0);
        assertThat(result.metric("quote_anchor_accuracy")).isEqualTo(1.0);
        assertThat(result.metric("bbox_coverage")).isEqualTo(1.0);
        ParserBenchmarkRunner.requireMinimums(
                List.of(result),
                Map.of(
                        "reading_order_f1", 1.0,
                        "quote_anchor_accuracy", 1.0,
                        "bbox_coverage", 1.0));
    }

    @Test
    @DisplayName("benchmark case can compare real PDF output against expected bbox fixtures")
    void benchmarkCanCompareRealPdfAgainstExpectedBboxes() throws Exception {
        var pdf = writePositionedPdf(List.of(
                run("CONTACT", 50f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("+6011-19822183", 50f, 700f),
                run("PROFILE", 320f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("Experienced business development executive.", 320f, 700f)));
        var expected = expectedTwoColumnDocument();

        var benchmark = ParserBenchmarkCase.fromPdf("two-column-real-pdf-bbox", pdf, """
                CONTACT
                +6011-19822183
                PROFILE
                Experienced business development executive.
                """, expected);
        var result = ParserBenchmarkRunner.evaluate(List.of(benchmark)).getFirst();

        assertThat(result.metric("bbox_iou")).isGreaterThanOrEqualTo(0.20);
        ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("bbox_iou", 0.20));
    }

    @Test
    @DisplayName("benchmark case gates table-cell recovery from a real bordered PDF table")
    void benchmarkCanCompareRealPdfAgainstExpectedTableCells() throws Exception {
        var pdf = writeBorderedTablePdf();
        var expected = expectedBorderedTableDocument();

        var benchmark = ParserBenchmarkCase.fromPdf("bordered-table-real-pdf", pdf, """
                Name
                Score
                Alex
                98
                """, expected);
        var result = ParserBenchmarkRunner.evaluate(List.of(benchmark)).getFirst();

        assertThat(result.metric("table_cell_f1")).isEqualTo(1.0);
        ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("table_cell_f1", 1.0));
    }

    @Test
    @DisplayName("real PDF table extraction suppresses duplicate text blocks for table cell content")
    void realPdfTableExtractionSuppressesDuplicateTextBlocks() throws Exception {
        var pdf = writeBorderedTablePdf();
        var document = ParserBenchmarkCase.fromPdf("bordered-table-no-duplicates", pdf, "")
                .document();

        var textBlocks = document.body().units().stream()
                .filter(unit -> unit.kind() == TrustUnitKind.TEXT_BLOCK)
                .map(unit -> unit.content().text())
                .toList();

        assertThat(textBlocks).noneMatch(text -> text.contains("Name"));
        assertThat(textBlocks).noneMatch(text -> text.contains("Score"));
        assertThat(textBlocks).noneMatch(text -> text.contains("Alex"));
        assertThat(textBlocks).noneMatch(text -> text.contains("98"));
    }

    @Test
    @DisplayName("benchmark case gates table-region IoU from a real bordered PDF table")
    void benchmarkCanCompareRealPdfAgainstExpectedTableRegion() throws Exception {
        var pdf = writeBorderedTablePdf();
        var expected = expectedBorderedTableDocument();

        var benchmark = ParserBenchmarkCase.fromPdf("bordered-table-region", pdf, "", expected);
        var result = ParserBenchmarkRunner.evaluate(List.of(benchmark)).getFirst();

        assertThat(result.metric("table_region_iou")).isGreaterThanOrEqualTo(0.95);
        ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("table_region_iou", 0.95));
    }

    @Test
    @DisplayName("real PDF bordered table extraction preserves cell-level bounding boxes")
    void realPdfBorderedTableExtractionPreservesCellBoundingBoxes() throws Exception {
        var pdf = writeBorderedTablePdf();
        var document = ParserBenchmarkCase.fromPdf("bordered-table-cell-bboxes", pdf, "")
                .document();

        var table = document.body().tables().getFirst();
        var tableCellUnits = document.body().units().stream()
                .filter(unit -> unit.kind() == TrustUnitKind.TABLE_CELL)
                .toList();

        assertThat(table.cells()).hasSize(4);
        assertThat(table.cells()).allMatch(cell -> cell.boundingBox().isPresent());
        assertThat(tableCellUnits).hasSize(4);
        assertThat(tableCellUnits)
                .allMatch(unit -> unit.location().boundingBox().isPresent());
    }

    private static TrustDocument document(String text) {
        var parsed = new ParsedDocument(
                "doc-fixture",
                List.of(new TextSection(
                        text,
                        new SourceLocation(
                                1, 1, 1, Math.max(1, (int) text.lines().count()), 0),
                        BlockKind.BODY,
                        Optional.of(new BoundingBox(0, 0, 1000, 1000)))),
                new DocumentMetadata("fixture.pdf", 1, Optional.empty()));
        return TrustDocument.fromParsed(
                        parsed, "sha256:fixture", new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of()))
                .withEvaluatedAuditGrade();
    }

    private static TrustDocument documentWithTable(String docId, BoundingBox unitBox) {
        var page = new TrustPage(1, 1000, 1000, true, "sha256:image");
        var unit = new TrustUnit(
                "unit-1",
                TrustUnitKind.TABLE_CELL,
                new TrustUnitLocation(1, Optional.of(unitBox), 1),
                new TrustUnitContent("Name | Score", "table-1"),
                new TrustUnitEvidence(List.of("span-1"), new Confidence(0.98, "fixture"), List.of()));
        var table = new TrustTable(
                "table-1",
                1,
                Optional.of(new BoundingBox(80, 80, 340, 220)),
                new Confidence(0.98, "fixture"),
                List.of(
                        new TrustTableCell(
                                "cell-1",
                                new TrustCellRange(1, 1),
                                new TrustCellRange(1, 1),
                                Optional.of(unitBox),
                                "Name"),
                        new TrustTableCell(
                                "cell-2",
                                new TrustCellRange(1, 1),
                                new TrustCellRange(2, 2),
                                Optional.of(new BoundingBox(300, 100, 420, 180)),
                                "Score")));
        return new TrustDocument(
                        docId,
                        new TrustDocumentSource(
                                docId + ".pdf",
                                "sha256:" + docId,
                                new DocumentMetadata(docId + ".pdf", 1, Optional.empty())),
                        new TrustDocumentBody(List.of(page), List.of(unit), List.of(table)),
                        new ParserRun("1.0.0", "table-lite", "fixture", List.of(), List.of()),
                        AuditGradeStatus.UNKNOWN)
                .withEvaluatedAuditGrade();
    }

    private static TrustDocument documentWithEvidenceSpan(String docId, String text, List<String> evidenceSpanIds) {
        var page = new TrustPage(1, 1000, 1000, true, "sha256:evidence-page");
        var unit = new TrustUnit(
                "unit-1",
                TrustUnitKind.TEXT_BLOCK,
                new TrustUnitLocation(1, Optional.of(new BoundingBox(100, 100, 800, 220)), 1),
                new TrustUnitContent(text, "section-1"),
                new TrustUnitEvidence(evidenceSpanIds, new Confidence(0.98, "evidence fixture"), List.of()));
        return new TrustDocument(
                        docId,
                        new TrustDocumentSource(
                                docId + ".pdf",
                                "sha256:" + docId,
                                new DocumentMetadata(docId + ".pdf", 1, Optional.empty())),
                        new TrustDocumentBody(List.of(page), List.of(unit), List.of()),
                        new ParserRun("1.0.0", "lite", "fixture", List.of(), List.of()),
                        AuditGradeStatus.UNKNOWN)
                .withEvaluatedAuditGrade();
    }

    private static TrustDocument ocrDocument(String text) {
        var page = new TrustPage(1, 1000, 1000, false, "sha256:ocr-page");
        var unit = new TrustUnit(
                "ocr-unit-1",
                TrustUnitKind.OCR_REGION,
                new TrustUnitLocation(1, Optional.of(new BoundingBox(100, 100, 800, 220)), 1),
                new TrustUnitContent(text, "ocr-page-1"),
                new TrustUnitEvidence(List.of("span-ocr-1"), new Confidence(0.96, "OCR fixture"), List.of()));
        return new TrustDocument(
                        "ocr-doc",
                        new TrustDocumentSource(
                                "ocr.pdf", "sha256:ocr-source", new DocumentMetadata("ocr.pdf", 1, Optional.empty())),
                        new TrustDocumentBody(List.of(page), List.of(unit), List.of()),
                        new ParserRun("1.0.0", "ocr", "pdfbox+ocr", List.of("rapidocr-mnn:local"), List.of()),
                        AuditGradeStatus.UNKNOWN)
                .withEvaluatedAuditGrade();
    }

    private static TrustDocument documentWithParserWarnings(List<ParserWarning> warnings) {
        return documentWithWarnings(warnings, List.of());
    }

    private static TrustDocument documentWithWarnings(
            List<ParserWarning> parserWarnings, List<ParserWarning> unitWarnings) {
        var page = new TrustPage(1, 1000, 1000, true, "sha256:warning-page");
        var unit = new TrustUnit(
                "warning-unit-1",
                TrustUnitKind.TEXT_BLOCK,
                new TrustUnitLocation(1, Optional.of(new BoundingBox(100, 100, 800, 220)), 1),
                new TrustUnitContent("Warning fixture", "warning-source-1"),
                new TrustUnitEvidence(
                        List.of("span-warning-1"), new Confidence(0.98, "warning fixture"), unitWarnings));
        return new TrustDocument(
                        "warning-doc",
                        new TrustDocumentSource(
                                "warning.pdf",
                                "sha256:warning-source",
                                new DocumentMetadata("warning.pdf", 1, Optional.empty())),
                        new TrustDocumentBody(List.of(page), List.of(unit), List.of()),
                        new ParserRun("1.0.0", "standard", "fixture", List.of(), parserWarnings),
                        AuditGradeStatus.UNKNOWN)
                .withEvaluatedAuditGrade();
    }

    private static ParserWarning severe(String code) {
        return new ParserWarning(code, ParserWarningSeverity.SEVERE, code + " fixture");
    }

    private static TrustDocument expectedTwoColumnDocument() {
        var page = new TrustPage(1, 1000, 1000, true, "sha256:expected-page");
        var contact = expectedUnit("unit-contact", "CONTACT", new BoundingBox(81.69, 75.75, 177.55, 90.91), 1);
        var phone = expectedUnit("unit-phone", "+6011-19822183", new BoundingBox(81.69, 101.01, 229.84, 116.17), 2);
        var profile = expectedUnit("unit-profile", "PROFILE", new BoundingBox(522.87, 75.75, 607.87, 90.91), 3);
        var summary = expectedUnit(
                "unit-summary",
                "Experienced business development executive.",
                new BoundingBox(522.87, 101.01, 926.12, 116.17),
                4);
        return new TrustDocument(
                        "expected-two-column",
                        new TrustDocumentSource(
                                "expected.pdf",
                                "sha256:expected",
                                new DocumentMetadata("expected.pdf", 1, Optional.empty())),
                        new TrustDocumentBody(List.of(page), List.of(contact, phone, profile, summary), List.of()),
                        new ParserRun("1.0.0", "lite", "fixture", List.of(), List.of()),
                        AuditGradeStatus.UNKNOWN)
                .withEvaluatedAuditGrade();
    }

    private static TrustDocument expectedBorderedTableDocument() {
        var tableBox = new BoundingBox(117.0, 90.0, 589.0, 193.0);
        var table = new TrustTable(
                "table-0001",
                1,
                Optional.of(tableBox),
                new Confidence(1.0, "expected fixture"),
                List.of(
                        expectedCell(0, 0, "Name"),
                        expectedCell(0, 1, "Score"),
                        expectedCell(1, 0, "Alex"),
                        expectedCell(1, 1, "98")));
        return new TrustDocument(
                        "expected-bordered-table",
                        new TrustDocumentSource(
                                "expected-table.pdf",
                                "sha256:expected-table",
                                new DocumentMetadata("expected-table.pdf", 1, Optional.empty())),
                        new TrustDocumentBody(
                                List.of(new TrustPage(1, 1000, 1000, true, "sha256:page")), List.of(), List.of(table)),
                        new ParserRun("1.0.0", "table-lite", "fixture", List.of(), List.of()),
                        AuditGradeStatus.UNKNOWN)
                .withEvaluatedAuditGrade();
    }

    private static TrustTableCell expectedCell(int row, int column, String text) {
        return new TrustTableCell(
                "cell-0001-%04d-%04d".formatted(row, column),
                new TrustCellRange(row, row),
                new TrustCellRange(column, column),
                Optional.empty(),
                text);
    }

    private static TrustUnit expectedUnit(String id, String text, BoundingBox box, int order) {
        return new TrustUnit(
                id,
                TrustUnitKind.TEXT_BLOCK,
                new TrustUnitLocation(1, Optional.of(box), order),
                new TrustUnitContent(text.strip(), id),
                new TrustUnitEvidence(List.of("span-" + id), new Confidence(1.0, "expected fixture"), List.of()));
    }

    private Path writePositionedPdf(List<PositionedRun> runs) throws IOException {
        var path = tempDir.resolve("benchmark-" + System.nanoTime() + ".pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                for (var run : runs) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(run.fontName()), run.fontSize());
                    stream.newLineAtOffset(run.x(), run.y());
                    stream.showText(run.text());
                    stream.endText();
                }
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path writeBorderedTablePdf() throws IOException {
        var path = tempDir.resolve("benchmark-table-" + System.nanoTime() + ".pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                float x0 = 72f;
                float x1 = 220f;
                float x2 = 360f;
                float y0 = 720f;
                float y1 = 680f;
                float y2 = 640f;
                drawLine(stream, x0, y0, x2, y0);
                drawLine(stream, x0, y1, x2, y1);
                drawLine(stream, x0, y2, x2, y2);
                drawLine(stream, x0, y0, x0, y2);
                drawLine(stream, x1, y0, x1, y2);
                drawLine(stream, x2, y0, x2, y2);
                writeText(stream, run("Name", 90f, 700f));
                writeText(stream, run("Score", 240f, 700f));
                writeText(stream, run("Alex", 90f, 660f));
                writeText(stream, run("98", 240f, 660f));
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private static void drawLine(PDPageContentStream stream, float x0, float y0, float x1, float y1)
            throws IOException {
        stream.moveTo(x0, y0);
        stream.lineTo(x1, y1);
        stream.stroke();
    }

    private static void writeText(PDPageContentStream stream, PositionedRun run) throws IOException {
        stream.beginText();
        stream.setFont(new PDType1Font(run.fontName()), run.fontSize());
        stream.newLineAtOffset(run.x(), run.y());
        stream.showText(run.text());
        stream.endText();
    }

    private static PositionedRun run(String text, float x, float y) {
        return run(text, x, y, 10f, Standard14Fonts.FontName.HELVETICA);
    }

    private static PositionedRun run(String text, float x, float y, float fontSize, Standard14Fonts.FontName fontName) {
        return new PositionedRun(text, x, y, fontSize, fontName);
    }

    private static ParserBenchmarkCase withSystemProperty(String key, String value, ThrowingBenchmarkSupplier supplier)
            throws Exception {
        String previous = System.getProperty(key);
        System.setProperty(key, value);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    private interface ThrowingBenchmarkSupplier {
        ParserBenchmarkCase get() throws Exception;
    }

    private record PositionedRun(String text, float x, float y, float fontSize, Standard14Fonts.FontName fontName) {}
}
