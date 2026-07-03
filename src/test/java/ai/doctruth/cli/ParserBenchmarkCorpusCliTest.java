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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.doctruth.BlockKind;
import ai.doctruth.BoundingBox;
import ai.doctruth.DocumentMetadata;
import ai.doctruth.ParsedDocument;
import ai.doctruth.ParserRun;
import ai.doctruth.ParserWarning;
import ai.doctruth.ParserWarningSeverity;
import ai.doctruth.SourceLocation;
import ai.doctruth.TextSection;
import ai.doctruth.TrustDocument;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI contracts for labeled parser benchmark corpus manifests. */
class ParserBenchmarkCorpusCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void benchmarkCorpusPrintsReadableSummaryAndPassesThresholds() throws Exception {
        Path manifest = writePassingManifest(Map.of("reading_order_f1", 1.0, "bbox_coverage", 1.0));
        var cli = cli();

        int code = cli.run(new String[] {"benchmark-corpus", manifest.toString()});

        assertThat(code).isZero();
        assertThat(cli.out())
                .contains("corpus: generated-parser-corpus")
                .contains("cases: 1")
                .contains("single-column-generated")
                .contains("parser_latency_p95")
                .contains("reading_order_f1: 1.000")
                .contains("thresholds: passed");
    }

    @Test
    void benchmarkCorpusJsonPrintsMachineReadableMetrics() throws Exception {
        Path manifest = writePassingManifest(Map.of("reading_order_f1", 1.0));
        var cli = cli();

        int code = cli.run(new String[] {"benchmark-corpus", manifest.toString(), "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("corpus").asText()).isEqualTo("generated-parser-corpus");
        assertThat(tree.path("passed").asBoolean()).isTrue();
        assertThat(tree.path("metrics").path("parser_latency_p50").asDouble()).isGreaterThanOrEqualTo(0.0);
        assertThat(tree.path("metrics").path("parser_latency_p95").asDouble()).isGreaterThanOrEqualTo(0.0);
        assertThat(tree.path("metrics").path("compact_llm_size_reduction_min").asDouble())
                .isGreaterThanOrEqualTo(0.0);
        assertThat(tree.path("cases")).hasSize(1);
        assertThat(tree.path("cases").get(0).path("name").asText()).isEqualTo("single-column-generated");
        assertThat(tree.path("cases")
                        .get(0)
                        .path("metrics")
                        .path("reading_order_f1")
                        .asDouble())
                .isEqualTo(1.0);
        assertThat(tree.path("cases").get(0).path("metrics").path("rss_peak_mb").asDouble())
                .isGreaterThanOrEqualTo(0.0);
        assertThat(tree.path("cases")
                        .get(0)
                        .path("metrics")
                        .path("model_cache_size_mb")
                        .asDouble())
                .isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void benchmarkCorpusJsonPrintsHumanLabeledMetadata() throws Exception {
        Path source = writePdf("PROFILE", "Experienced operator");
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        Files.writeString(
                tempDir.resolve("expected.json"),
                expectedDocument("PROFILE\nExperienced operator").toJsonFull());
        Files.writeString(tempDir.resolve("human-corpus.json"), """
                {
                  "name": "human-labeled-cli-corpus",
                  "kind": "human-labeled",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "requiredMetrics": ["reading_order_f1", "bbox_coverage"]
                  },
                  "minimums": {
                    "reading_order_f1": 1.0,
                    "bbox_coverage": 1.0
                  },
                  "cases": [
                    {
                      "name": "human-labeled-cli-case",
                      "labelId": "layout-v1-0001",
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));
        var cli = cli();

        int code = cli.run(new String[] {
            "benchmark-corpus", tempDir.resolve("human-corpus.json").toString(), "--json"
        });

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("kind").asText()).isEqualTo("human-labeled");
        assertThat(tree.path("labelSetVersion").asText()).isEqualTo("layout-v1");
        assertThat(tree.path("requiredMetrics").findValuesAsText("")).isEmpty();
        assertThat(tree.path("requiredMetrics").get(0).asText()).isEqualTo("reading_order_f1");
        assertThat(tree.path("requiredMetrics").get(1).asText()).isEqualTo("bbox_coverage");
    }

    @Test
    void benchmarkCorpusJsonPrintsParserAccuracyCoverageMetadata() throws Exception {
        Path source = writePdf("PROFILE", "Experienced operator");
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        Files.writeString(
                tempDir.resolve("expected.json"),
                expectedDocument("PROFILE\nExperienced operator").toJsonFull());
        Files.writeString(tempDir.resolve("parser-accuracy-corpus.json"), """
                {
                  "name": "parser-accuracy-cli-corpus",
                  "kind": "human-labeled",
                  "qualityProfile": "parser-accuracy",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "reviewType": "human-reviewed",
                    "requiredMetrics": [
                      "reading_order_f1",
                      "quote_anchor_accuracy",
                      "bbox_coverage",
                      "bbox_iou",
                      "evidence_span_accuracy",
                      "table_cell_f1",
                      "ocr_text_accuracy"
                    ],
                    "requiredTags": ["multi-layout", "table", "ocr", "bbox", "source-map"],
                    "minCasesPerTag": 1,
                    "minTotalCases": 1
                  },
                  "minimums": {
                    "reading_order_f1": 1.0,
                    "quote_anchor_accuracy": 1.0,
                    "bbox_coverage": 1.0,
                    "bbox_iou": 0.0,
                    "evidence_span_accuracy": 1.0,
                    "table_cell_f1": 1.0,
                    "ocr_text_accuracy": 1.0
                  },
                  "cases": [
                    {
                      "name": "multi-layout-cli-case",
                      "labelId": "layout-v1-0002",
                      "tags": ["multi-layout", "table", "ocr", "bbox", "source-map"],
                      "source": "%s",
                      "sourceSha256": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(
                        tempDir.relativize(source), sha256(source)));
        var cli = cli();

        int code = cli.run(new String[] {
            "benchmark-corpus", tempDir.resolve("parser-accuracy-corpus.json").toString(), "--json"
        });

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("qualityProfile").asText()).isEqualTo("parser-accuracy");
        assertThat(tree.path("reviewType").asText()).isEqualTo("human-reviewed");
        assertThat(tree.path("requiredTags").get(0).asText()).isEqualTo("multi-layout");
        assertThat(tree.path("requiredTags").get(4).asText()).isEqualTo("source-map");
        assertThat(tree.path("minCasesPerTag").path("multi-layout").asInt()).isEqualTo(1);
        assertThat(tree.path("minCasesPerTag").path("source-map").asInt()).isEqualTo(1);
        assertThat(tree.path("minTotalCases").asInt()).isEqualTo(1);
        var caseNode = tree.path("cases").get(0);
        assertThat(caseNode.path("labelId").asText()).isEqualTo("layout-v1-0002");
        assertThat(caseNode.path("tags").get(0).asText()).isEqualTo("multi-layout");
        assertThat(caseNode.path("tags").get(4).asText()).isEqualTo("source-map");

        var readableCli = cli();
        int readableCode = readableCli.run(new String[] {
            "benchmark-corpus", tempDir.resolve("parser-accuracy-corpus.json").toString()
        });

        assertThat(readableCode).isZero();
        assertThat(readableCli.out())
                .contains("kind: human-labeled")
                .contains("qualityProfile: parser-accuracy")
                .contains("reviewType: human-reviewed")
                .contains("labelSetVersion: layout-v1")
                .contains("requiredTags: multi-layout, table, ocr, bbox, source-map")
                .contains("minCasesPerTag:")
                .contains("multi-layout=1")
                .contains("source-map=1")
                .contains("minTotalCases: 1")
                .contains("labelId: layout-v1-0002")
                .contains("tags: multi-layout, table, ocr, bbox, source-map");
    }

    @Test
    void benchmarkCorpusWritesRecordedReportArtifact() throws Exception {
        Path manifest = writeParserAccuracyManifest();
        Path report = tempDir.resolve("reports/parser-accuracy-report.json");
        var cli = cli();

        int code = cli.run(
                new String[] {"benchmark-corpus", manifest.toString(), "--json", "--report-out", report.toString()});

        assertThat(code).isZero();
        assertThat(report).exists();
        var stdout = MAPPER.readTree(cli.out());
        var recorded = MAPPER.readTree(Files.readString(report));
        assertThat(recorded.path("reportFormat").asText()).isEqualTo("doctruth.parser-benchmark.report.v1");
        assertThat(recorded.path("manifest").asText()).endsWith("parser-accuracy-report-corpus.json");
        assertThat(recorded.path("manifestSha256").asText()).startsWith("sha256:");
        assertThat(recorded.path("caseCount").asInt()).isEqualTo(1);
        assertThat(recorded.path("casesPerTag").path("multi-layout").asInt()).isEqualTo(1);
        assertThat(recorded.path("casesPerTag").path("source-map").asInt()).isEqualTo(1);
        assertThat(recorded.path("coverageRequired").path("source-map").asInt()).isEqualTo(1);
        assertThat(recorded.path("coverageSatisfied").path("source-map").asBoolean())
                .isTrue();
        assertThat(recorded.path("casesPerFixtureType").path("two-column").asInt())
                .isEqualTo(1);
        assertThat(recorded.path("fixtureCoverageRequired").path("scanned-ocr").asInt())
                .isEqualTo(1);
        assertThat(recorded.path("fixtureCoverageSatisfied").path("invoice").asBoolean())
                .isTrue();
        assertThat(recorded.path("casesPerBehavior").path("xy-cut-edge").asInt())
                .isEqualTo(1);
        assertThat(recorded.path("behaviorCoverageRequired")
                        .path("structure-tree-preference")
                        .asInt())
                .isEqualTo(1);
        assertThat(recorded.path("behaviorCoverageSatisfied")
                        .path("table-cluster-heuristics")
                        .asBoolean())
                .isTrue();
        assertThat(recorded.path("validityInputs").path("sourceHashes").asBoolean())
                .isTrue();
        assertThat(recorded.path("validityInputs").path("manifestHash").asBoolean())
                .isTrue();
        assertThat(recorded.path("validityInputs").path("parserConfig").asText())
                .isEqualTo("TrustDocument");
        assertThat(recorded.path("validityInputs").path("modelCacheManifest").asText())
                .isEqualTo("not-required");
        assertThat(recorded.path("validityInputs").path("thresholds").asBoolean())
                .isTrue();
        assertThat(recorded.path("validityInputs").path("expectedLabels").asBoolean())
                .isTrue();
        assertThat(recorded.path("validityInputs").path("actualTrustDocument").asBoolean())
                .isTrue();
        assertThat(recorded.path("minimums").path("reading_order_f1").asDouble())
                .isEqualTo(1.0);
        assertThat(recorded.path("maximums").isObject()).isTrue();
        assertThat(recorded.path("corpus").asText())
                .isEqualTo(stdout.path("corpus").asText());
        assertThat(recorded.path("qualityProfile").asText()).isEqualTo("parser-accuracy");
        assertThat(recorded.path("reviewType").asText()).isEqualTo("human-reviewed");
        assertThat(recorded.path("passed").asBoolean()).isTrue();
        assertThat(recorded.path("metrics").path("parser_latency_p95").asDouble())
                .isGreaterThanOrEqualTo(0.0);
        assertThat(recorded.path("metrics").path("opendataloader_nid").asDouble())
                .isEqualTo(0.91);
        assertThat(recorded.path("metrics").path("opendataloader_teds").asDouble())
                .isEqualTo(0.52);
        assertThat(recorded.path("metrics").path("opendataloader_mhs").asDouble())
                .isEqualTo(0.76);
        assertThat(recorded.path("metrics").path("opendataloader_speed").asDouble())
                .isEqualTo(0.015);
        assertThat(recorded.path("externalMetrics")
                        .path("opendataloader")
                        .path("evaluationSha256")
                        .asText())
                .startsWith("sha256:");
        assertThat(recorded.path("cases").get(0).path("labelId").asText()).isEqualTo("layout-v1-report-0001");
        assertThat(recorded.path("cases").get(0).path("sourceSha256").asText()).startsWith("sha256:");
        assertThat(recorded.path("cases").get(0).path("fixtureTypes"))
                .extracting(node -> node.asText())
                .contains("simple-single-column", "two-column", "sidebar-resume", "invoice", "mixed-layout");
        assertThat(recorded.path("cases").get(0).path("behaviors"))
                .extracting(node -> node.asText())
                .contains("xy-cut-edge", "safety-filter", "structure-tree-preference", "table-cluster-heuristics");
        assertThat(recorded.path("cases")
                        .get(0)
                        .path("replay")
                        .path("sourceRefReplayable")
                        .asBoolean())
                .isTrue();
        assertThat(recorded.path("cases")
                        .get(0)
                        .path("replay")
                        .path("quoteReplayable")
                        .asBoolean())
                .isTrue();
        assertThat(recorded.path("cases")
                        .get(0)
                        .path("replay")
                        .path("evidenceSpanReplayable")
                        .asBoolean())
                .isTrue();
        assertThat(recorded.path("cases").get(0).path("tags"))
                .extracting(node -> node.asText())
                .contains("multi-layout", "table", "ocr", "bbox", "source-map");
    }

    @Test
    void verifyBenchmarkReportAcceptsRecordedReportArtifact() throws Exception {
        Path manifest = writeParserAccuracyManifest();
        Path report = tempDir.resolve("reports/parser-accuracy-report.json");
        var writer = cli();
        assertThat(writer.run(new String[] {
                    "benchmark-corpus", manifest.toString(), "--json", "--report-out", report.toString()
                }))
                .isZero();
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isZero();
        assertThat(verifier.out()).contains("benchmark report verified");
    }

    @Test
    void verifyBenchmarkReportRequiresReportArgument() {
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report"});

        assertThat(code).isEqualTo(2);
        assertThat(verifier.err()).contains("usage: doctruth verify-benchmark-report <report.json>");
    }

    @Test
    void verifyBenchmarkReportRejectsTamperedCoverageCounts() throws Exception {
        Path manifest = writeParserAccuracyManifest();
        Path report = tempDir.resolve("reports/parser-accuracy-report.json");
        var writer = cli();
        assertThat(writer.run(new String[] {
                    "benchmark-corpus", manifest.toString(), "--json", "--report-out", report.toString()
                }))
                .isZero();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        recorded.put("caseCount", 999);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("caseCount mismatch");
    }

    @Test
    void verifyBenchmarkReportRejectsExtraRecordedCoverageTags() throws Exception {
        Path manifest = writeParserAccuracyManifest();
        Path report = tempDir.resolve("reports/parser-accuracy-report.json");
        var writer = cli();
        assertThat(writer.run(new String[] {
                    "benchmark-corpus", manifest.toString(), "--json", "--report-out", report.toString()
                }))
                .isZero();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var casesPerTag = (com.fasterxml.jackson.databind.node.ObjectNode) recorded.path("casesPerTag");
        casesPerTag.put("forged-tag", 1);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("casesPerTag mismatch");
    }

    @Test
    void verifyBenchmarkReportRejectsTamperedCoverageThresholds() throws Exception {
        Path manifest = writeParserAccuracyManifest();
        Path report = tempDir.resolve("reports/parser-accuracy-report.json");
        var writer = cli();
        assertThat(writer.run(new String[] {
                    "benchmark-corpus", manifest.toString(), "--json", "--report-out", report.toString()
                }))
                .isZero();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var minCasesPerTag = (com.fasterxml.jackson.databind.node.ObjectNode) recorded.path("minCasesPerTag");
        minCasesPerTag.put("source-map", 2);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("minCasesPerTag mismatch");
    }

    @Test
    void verifyBenchmarkReportRejectsTamperedCoverageSatisfaction() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var coverage = (com.fasterxml.jackson.databind.node.ObjectNode) recorded.path("coverageSatisfied");
        coverage.put("source-map", false);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("coverageSatisfied mismatch");
    }

    @Test
    void verifyBenchmarkReportRejectsTamperedFixtureCoverage() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var coverage = (com.fasterxml.jackson.databind.node.ObjectNode) recorded.path("fixtureCoverageSatisfied");
        coverage.put("invoice", false);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("fixtureCoverageSatisfied mismatch");
    }

    @Test
    void verifyBenchmarkReportRejectsTamperedBehaviorCoverage() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var coverage = (com.fasterxml.jackson.databind.node.ObjectNode) recorded.path("behaviorCoverageSatisfied");
        coverage.put("xy-cut-edge", false);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("behaviorCoverageSatisfied mismatch");
    }

    @Test
    void verifyBenchmarkReportRejectsTamperedReplayValidityInputs() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var validity = (com.fasterxml.jackson.databind.node.ObjectNode) recorded.path("validityInputs");
        validity.put("actualTrustDocument", false);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("validityInputs mismatch");
    }

    @Test
    void verifyBenchmarkReportRejectsTamperedCaseReplayEvidence() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var replay = (com.fasterxml.jackson.databind.node.ObjectNode)
                recorded.path("cases").get(0).path("replay");
        replay.put("evidenceSpanReplayable", false);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("case replay mismatch").contains("evidenceSpanReplayable");
    }

    @Test
    void verifyBenchmarkReportRejectsTamperedMetricsBelowMinimum() throws Exception {
        Path manifest = writeParserAccuracyManifest();
        Path report = tempDir.resolve("reports/parser-accuracy-report.json");
        var writer = cli();
        assertThat(writer.run(new String[] {
                    "benchmark-corpus", manifest.toString(), "--json", "--report-out", report.toString()
                }))
                .isZero();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var metrics = (com.fasterxml.jackson.databind.node.ObjectNode) recorded.path("metrics");
        metrics.put("reading_order_f1", 0.0);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("minimum threshold failed").contains("reading_order_f1");
    }

    @Test
    void verifyBenchmarkReportRejectsTamperedAggregateMetrics() throws Exception {
        Path manifest = writeParserAccuracyManifest();
        Path report = tempDir.resolve("reports/parser-accuracy-report.json");
        var writer = cli();
        assertThat(writer.run(new String[] {
                    "benchmark-corpus", manifest.toString(), "--json", "--report-out", report.toString()
                }))
                .isZero();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var metrics = (com.fasterxml.jackson.databind.node.ObjectNode) recorded.path("metrics");
        metrics.put("parser_latency_p95", 999999.0);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("aggregate metric mismatch").contains("parser_latency_p95");
    }

    @Test
    void verifyBenchmarkReportRejectsTamperedExternalMetrics() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var metrics = (com.fasterxml.jackson.databind.node.ObjectNode) recorded.path("metrics");
        metrics.put("opendataloader_nid", 0.0);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("external metrics mismatch").contains("opendataloader_nid");
    }

    @Test
    void benchmarkCorpusExportsOpenDataLoaderPredictionArtifacts() throws Exception {
        Path manifest = writeParserAccuracyManifest();
        Path prediction = tempDir.resolve("prediction/doctruth");
        var cli = cli();

        int code = cli.run(new String[] {
            "benchmark-corpus", manifest.toString(), "--json", "--opendataloader-prediction-out", prediction.toString()
        });

        assertThat(code).isZero();
        assertThat(prediction.resolve("markdown/layout-v1-report-0001.md")).exists();
        assertThat(Files.readString(prediction.resolve("markdown/layout-v1-report-0001.md")))
                .contains("PROFILE")
                .contains("Experienced operator");
        var summary = MAPPER.readTree(Files.readString(prediction.resolve("summary.json")));
        assertThat(summary.path("engine_name").asText()).isEqualTo("doctruth");
        assertThat(summary.path("document_count").asInt()).isEqualTo(1);
        var stdout = MAPPER.readTree(cli.out());
        assertThat(stdout.path("externalArtifacts")
                        .path("opendataloaderPrediction")
                        .path("engine")
                        .asText())
                .isEqualTo("doctruth");
    }

    @Test
    void verifyBenchmarkReportRejectsUnsupportedReportFormat() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        recorded.put("reportFormat", "doctruth.parser-benchmark.report.v0");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("unsupported benchmark report format");
    }

    @Test
    void verifyBenchmarkReportRejectsFailedReport() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        recorded.put("passed", false);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("benchmark report did not pass");
    }

    @Test
    void verifyBenchmarkReportRejectsNonObjectCasesPerTag() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        recorded.put("casesPerTag", "multi-layout=1");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("casesPerTag mismatch").contains("expected object");
    }

    @Test
    void verifyBenchmarkReportRejectsNonIntegerCasesPerTag() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var casesPerTag = (com.fasterxml.jackson.databind.node.ObjectNode) recorded.path("casesPerTag");
        casesPerTag.put("multi-layout", "one");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err())
                .contains("casesPerTag mismatch for multi-layout")
                .contains("expected integer");
    }

    @Test
    void verifyBenchmarkReportRejectsMissingMetricsObject() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        recorded.put("metrics", "missing");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("benchmark report missing metrics");
    }

    @Test
    void verifyBenchmarkReportRejectsMissingCaseMetricForAggregate() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var caseMetrics = (com.fasterxml.jackson.databind.node.ObjectNode)
                recorded.path("cases").get(0).path("metrics");
        caseMetrics.remove("parser_latency_ms");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("aggregate metric mismatch").contains("missing case metrics");
    }

    @Test
    void verifyBenchmarkReportRejectsSourceHashMismatch() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var firstCase = (com.fasterxml.jackson.databind.node.ObjectNode)
                recorded.path("cases").get(0);
        firstCase.put("sourceSha256", "sha256:" + "0".repeat(64));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("sourceSha256 mismatch");
    }

    @Test
    void verifyBenchmarkReportRejectsCorpusNameMismatch() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        recorded.put("corpus", "forged-corpus");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("corpus mismatch");
    }

    @Test
    void verifyBenchmarkReportRejectsRequiredTagsMismatch() throws Exception {
        Path report = writeRecordedBenchmarkReport();
        var recorded = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(Files.readString(report));
        var requiredTags = (com.fasterxml.jackson.databind.node.ArrayNode) recorded.path("requiredTags");
        requiredTags.removeAll();
        requiredTags.add("forged-tag");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), recorded);
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("requiredTags mismatch");
    }

    @Test
    void verifyBenchmarkReportRejectsChangedManifest() throws Exception {
        Path manifest = writeParserAccuracyManifest();
        Path report = tempDir.resolve("reports/parser-accuracy-report.json");
        var writer = cli();
        assertThat(writer.run(new String[] {
                    "benchmark-corpus", manifest.toString(), "--json", "--report-out", report.toString()
                }))
                .isZero();
        Files.writeString(
                manifest, Files.readString(manifest).replace("parser-accuracy-report-corpus", "changed-corpus"));
        var verifier = cli();

        int code = verifier.run(new String[] {"verify-benchmark-report", report.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(verifier.err()).contains("manifestSha256 mismatch");
    }

    @Test
    void benchmarkCorpusOfflineRejectsUncachedRemoteFixtures() throws Exception {
        Files.writeString(tempDir.resolve("expected.md"), "Remote Fixture\n");
        Files.writeString(
                tempDir.resolve("expected.json"),
                expectedDocument("Remote Fixture").toJsonFull());
        Files.writeString(tempDir.resolve("remote-corpus.json"), """
                {
                  "name": "offline-remote-corpus",
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "offline-remote-pdf",
                      "sourceUrl": "http://127.0.0.1:1/remote.pdf",
                      "sourceSha256": "sha256:%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted("a".repeat(64)));
        var cli = cli();

        int code = cli.run(new String[] {
            "benchmark-corpus", tempDir.resolve("remote-corpus.json").toString(), "--offline"
        });

        assertThat(code).isEqualTo(1);
        assertThat(cli.err()).contains("offline-remote-pdf").contains("offline mode refuses remote benchmark source");
    }

    @Test
    void benchmarkCorpusThresholdFailureReturnsRuntimeError() throws Exception {
        Path manifest = writePassingManifest(Map.of("reading_order_f1", 1.01));
        var cli = cli();

        int code = cli.run(new String[] {"benchmark-corpus", manifest.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(cli.err())
                .contains("parser benchmark thresholds failed")
                .contains("single-column-generated")
                .contains("reading_order_f1");
    }

    @Test
    void benchmarkCorpusOcrLabelFailureReturnsRuntimeError() throws Exception {
        Path source = writeBlankPdf();
        Path worker = writeFakeOcrWorker("OCR benchmark text", 0.96);
        Path runtime = writeFakeOcrRuntime(worker, "OCR benchmark text", 0.96);
        Files.writeString(tempDir.resolve("expected-ocr.md"), "Different OCR label\n");
        Files.writeString(
                tempDir.resolve("expected-ocr.json"),
                expectedDocument("Different OCR label").toJsonFull());
        Files.writeString(tempDir.resolve("ocr-corpus.json"), """
                {
                  "name": "ocr-corpus",
                  "minimums": {"ocr_text_accuracy": 1.0},
                  "cases": [
                    {
                      "name": "ocr-wrong-label",
                      "source": "%s",
                      "preset": "ocr",
                      "expectedMarkdown": "expected-ocr.md",
                      "expectedDocument": "expected-ocr.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));
        var cli = cli();

        int code = withSystemProperties(
                Map.of("doctruth.runtime.command", runtime.toString(), "doctruth.ocr.command", worker.toString()),
                () -> cli.run(new String[] {
                    "benchmark-corpus", tempDir.resolve("ocr-corpus.json").toString()
                }));

        assertThat(code).isEqualTo(1);
        assertThat(cli.err())
                .contains("parser benchmark thresholds failed")
                .contains("ocr-wrong-label")
                .contains("ocr_text_accuracy")
                .contains("minimum=1.0");
    }

    @Test
    void benchmarkCorpusMaximumThresholdFailureReturnsRuntimeError() throws Exception {
        Path source = writePdf("Warning Fixture");
        Files.writeString(tempDir.resolve("expected.md"), "Warning Fixture\n");
        Files.writeString(
                tempDir.resolve("expected.json"),
                expectedDocumentWithParserWarning("Warning Fixture", "layout_low_confidence")
                        .toJsonFull());
        Files.writeString(tempDir.resolve("warning-corpus.json"), """
                {
                  "name": "warning-corpus",
                  "minimums": {"reading_order_f1": 1.0},
                  "maximums": {"strict_warning_false_negative_rate": 0.02},
                  "cases": [
                    {
                      "name": "missing-warning-case",
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));
        var cli = cli();

        int code = cli.run(new String[] {
            "benchmark-corpus", tempDir.resolve("warning-corpus.json").toString()
        });

        assertThat(code).isEqualTo(1);
        assertThat(cli.err())
                .contains("parser benchmark thresholds failed")
                .contains("missing-warning-case")
                .contains("strict_warning_false_negative_rate")
                .contains("maximum=0.02");
    }

    @Test
    void benchmarkCorpusLatencyMaximumFailureUsesAggregateMetrics() throws Exception {
        Path source = writePdf("Work Experience", "Java Engineer");
        Files.writeString(tempDir.resolve("expected.md"), "Work Experience\nJava Engineer\n");
        Files.writeString(
                tempDir.resolve("expected.json"),
                expectedDocument("Work Experience\nJava Engineer").toJsonFull());
        Files.writeString(tempDir.resolve("latency-corpus.json"), """
                {
                  "name": "latency-corpus",
                  "minimums": {"reading_order_f1": 1.0},
                  "maximums": {"parser_latency_p95": 0.0},
                  "cases": [
                    {
                      "name": "latency-case",
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));
        var cli = cli();

        int code = cli.run(new String[] {
            "benchmark-corpus", tempDir.resolve("latency-corpus.json").toString()
        });

        assertThat(code).isEqualTo(1);
        assertThat(cli.err())
                .contains("parser benchmark thresholds failed")
                .contains("corpus parser_latency_p95")
                .contains("maximum=0.0");
    }

    @Test
    void benchmarkCorpusCompactMinimumFailureUsesAggregateMetrics() throws Exception {
        Path manifest = writePassingManifest(Map.of(
                "reading_order_f1", 1.0,
                "compact_llm_size_reduction_min", 1.0));
        var cli = cli();

        int code = cli.run(new String[] {"benchmark-corpus", manifest.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(cli.err())
                .contains("parser benchmark thresholds failed")
                .contains("corpus compact_llm_size_reduction_min")
                .contains("minimum=1.0");
    }

    @Test
    void benchmarkCorpusRejectsUnknownOption() throws Exception {
        Path manifest = writePassingManifest(Map.of("reading_order_f1", 1.0));
        var cli = cli();

        int code = cli.run(new String[] {"benchmark-corpus", manifest.toString(), "--wat"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("unknown benchmark-corpus option");
    }

    @Test
    void benchmarkCorpusRequiresManifestArgument() {
        var cli = cli();

        int code = cli.run(new String[] {"benchmark-corpus"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("usage: doctruth benchmark-corpus");
    }

    private Path writePassingManifest(Map<String, Double> minimums) throws IOException {
        Path source = writePdf("Work Experience", "Java Engineer");
        Files.writeString(tempDir.resolve("expected.md"), "Work Experience\nJava Engineer\n");
        Files.writeString(
                tempDir.resolve("expected.json"),
                expectedDocument("Work Experience\nJava Engineer").toJsonFull());
        Files.writeString(tempDir.resolve("corpus.json"), """
                {
                  "name": "generated-parser-corpus",
                  "minimums": %s,
                  "cases": [
                    {
                      "name": "single-column-generated",
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(
                        MAPPER.writeValueAsString(minimums), tempDir.relativize(source)));
        return tempDir.resolve("corpus.json");
    }

    private Path writeParserAccuracyManifest() throws IOException {
        Path source = writePdf("PROFILE", "Experienced operator");
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        Files.writeString(
                tempDir.resolve("expected.json"),
                expectedDocument("PROFILE\nExperienced operator").toJsonFull());
        Files.writeString(tempDir.resolve("opendataloader-evaluation.json"), """
                {
                  "summary": {
                    "engine_name": "doctruth-runtime",
                    "engine_version": "test",
                    "document_count": 1,
                    "elapsed_per_doc": 0.015
                  },
                  "metrics": {
                    "score": {
                      "nid_mean": 0.91,
                      "teds_mean": 0.52,
                      "mhs_mean": 0.76
                    }
                  }
                }
                """);
        Files.writeString(tempDir.resolve("parser-accuracy-report-corpus.json"), """
                {
                  "name": "parser-accuracy-report-corpus",
                  "kind": "human-labeled",
                  "qualityProfile": "parser-accuracy",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "reviewType": "human-reviewed",
                    "requiredMetrics": [
                      "reading_order_f1",
                      "quote_anchor_accuracy",
                      "bbox_coverage",
                      "bbox_iou",
                      "evidence_span_accuracy",
                      "table_cell_f1",
                      "ocr_text_accuracy"
                    ],
                    "requiredTags": ["multi-layout", "table", "ocr", "bbox", "source-map"],
                    "minCasesPerTag": 1,
                    "requiredFixtureTypes": [
                      "simple-single-column",
                      "two-column",
                      "sidebar-resume",
                      "table",
                      "borderless-table",
                      "scanned-ocr",
                      "invoice",
                      "mixed-layout"
                    ],
                    "minCasesPerFixtureType": 1,
                    "requiredBehaviors": [
                      "xy-cut-edge",
                      "safety-filter",
                      "structure-tree-preference",
                      "table-cluster-heuristics"
                    ],
                    "minCasesPerBehavior": 1,
                    "minTotalCases": 1
                  },
                  "minimums": {
                    "reading_order_f1": 1.0,
                    "quote_anchor_accuracy": 1.0,
                    "bbox_coverage": 1.0,
                    "bbox_iou": 0.0,
                    "evidence_span_accuracy": 1.0,
                    "table_cell_f1": 1.0,
                    "ocr_text_accuracy": 1.0,
                    "opendataloader_nid": 0.90,
                    "opendataloader_teds": 0.50,
                    "opendataloader_mhs": 0.74
                  },
                  "maximums": {
                    "opendataloader_speed": 0.02
                  },
                  "externalEvaluations": {
                    "opendataloader": "opendataloader-evaluation.json"
                  },
                  "cases": [
                    {
                      "name": "multi-layout-report-case",
                      "labelId": "layout-v1-report-0001",
                      "tags": ["multi-layout", "table", "ocr", "bbox", "source-map"],
                      "fixtureTypes": [
                        "simple-single-column",
                        "two-column",
                        "sidebar-resume",
                        "table",
                        "borderless-table",
                        "scanned-ocr",
                        "invoice",
                        "mixed-layout"
                      ],
                      "behaviors": [
                        "xy-cut-edge",
                        "safety-filter",
                        "structure-tree-preference",
                        "table-cluster-heuristics"
                      ],
                      "source": "%s",
                      "sourceSha256": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(
                        tempDir.relativize(source), sha256(source)));
        return tempDir.resolve("parser-accuracy-report-corpus.json");
    }

    private Path writeRecordedBenchmarkReport() throws IOException {
        Path manifest = writeParserAccuracyManifest();
        Path report = tempDir.resolve("reports/parser-accuracy-report.json");
        var writer = cli();
        assertThat(writer.run(new String[] {
                    "benchmark-corpus", manifest.toString(), "--json", "--report-out", report.toString()
                }))
                .isZero();
        return report;
    }

    private Path writePdf(String... lines) throws IOException {
        var path = tempDir.resolve("fixture.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                float y = 720f;
                for (var line : lines) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f);
                    stream.newLineAtOffset(72f, y);
                    stream.showText(line);
                    stream.endText();
                    y -= 20f;
                }
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path writeFakeOcrRuntime(Path worker, String text, double confidence) throws IOException {
        var path = tempDir.resolve("fake-ocr-runtime-" + Math.round(confidence * 100));
        Files.writeString(path, """
                #!/usr/bin/env sh
                cat >/dev/null
                test "$DOCTRUTH_RUNTIME_MODEL_COMMAND" = "%s"
                cat <<'JSON'
                {"docId":"sha256:ocr-benchmark","source":{"sourceFilename":"blank.pdf","sourceHash":"sha256:ocr-benchmark","metadata":{"sourceFilename":"blank.pdf","pageCount":1}},"body":{"pages":[{"pageNumber":1,"width":1000,"height":1000,"textLayerAvailable":false,"imageHash":"sha256:image"}],"units":[{"unitId":"unit-0001","kind":"OCR_REGION","page":1,"text":"%s","evidenceSpanIds":["span-0001"],"location":{"page":1,"readingOrder":1,"boundingBox":{"x0":10,"y0":20,"x1":200,"y1":80}},"sourceObjectId":"ocr-0001","confidence":{"score":%s,"rationale":"OCR page confidence"},"warnings":[]}],"tables":[]},"parserRun":{"parserVersion":"runtime-test","preset":"ocr","backend":"rust-sidecar+model-worker","models":["ocr-router:v1"],"warnings":[]},"auditGradeStatus":"AUDIT_GRADE"}
                JSON
                """.formatted(worker.toString(), text, Double.toString(confidence)));
        path.toFile().setExecutable(true);
        return path;
    }

    private Path writeBlankPdf() throws IOException {
        var path = tempDir.resolve("blank.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path writeFakeOcrWorker(String text, double confidence) throws IOException {
        var path = tempDir.resolve("fake-ocr-worker");
        Files.writeString(path, """
                #!/usr/bin/env sh
                python3 -c '
                import json
                import sys
                request = json.loads(sys.stdin.read())
                assert request["fileType"] == "png"
                print(json.dumps({
                  "ok": True,
                  "engine": "mnn",
                  "text": "%s",
                  "averageConfidence": %.2f,
                  "pages": [],
                  "warnings": []
                }))
                '
                """.formatted(text, confidence));
        path.toFile().setExecutable(true);
        return path;
    }

    private static TrustDocument expectedDocument(String text) {
        var parsed = new ParsedDocument(
                "expected-doc",
                List.of(new TextSection(
                        text,
                        new SourceLocation(
                                1, 1, 1, Math.max(1, (int) text.lines().count()), 0),
                        BlockKind.BODY,
                        Optional.of(new BoundingBox(100, 100, 500, 200)))),
                new DocumentMetadata("expected.pdf", 1, Optional.empty()));
        return TrustDocument.fromParsed(
                parsed, "sha256:expected", new ParserRun("1.0.0", "lite", "fixture", List.of(), List.of()));
    }

    private static String sha256(Path path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            var builder = new StringBuilder("sha256:");
            for (byte value : digest) {
                builder.append("%02x".formatted(value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static TrustDocument expectedDocumentWithParserWarning(String text, String warningCode) {
        var parsed = new ParsedDocument(
                "expected-doc",
                List.of(new TextSection(
                        text,
                        new SourceLocation(
                                1, 1, 1, Math.max(1, (int) text.lines().count()), 0),
                        BlockKind.BODY,
                        Optional.of(new BoundingBox(100, 100, 500, 200)))),
                new DocumentMetadata("expected.pdf", 1, Optional.empty()));
        return TrustDocument.fromParsed(
                parsed,
                "sha256:expected",
                new ParserRun(
                        "1.0.0",
                        "lite",
                        "fixture",
                        List.of(),
                        List.of(new ParserWarning(
                                warningCode, ParserWarningSeverity.SEVERE, "expected warning fixture"))));
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

    private static int withSystemProperty(String key, String value, ThrowingIntSupplier supplier) throws Exception {
        String previous = System.getProperty(key);
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

    private static int withSystemProperties(Map<String, String> properties, ThrowingIntSupplier supplier)
            throws Exception {
        var previous = new java.util.LinkedHashMap<String, String>();
        properties.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        try {
            return supplier.getAsInt();
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

    @FunctionalInterface
    private interface ThrowingIntSupplier {
        int getAsInt() throws Exception;
    }
}
