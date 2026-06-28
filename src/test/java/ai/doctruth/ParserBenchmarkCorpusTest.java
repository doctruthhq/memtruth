package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for labeled parser benchmark corpus manifests. */
class ParserBenchmarkCorpusTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("corpus manifest loads relative fixtures and evaluates thresholds")
    void manifestLoadsFixturesAndEvaluatesThresholds() throws Exception {
        var source = writePdf("Work Experience", "Java Engineer");
        var expected = expectedDocument("Work Experience\nJava Engineer");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "Work Experience\nJava Engineer\n");
        var manifest = writeManifest("""
                {
                  "name": "generated-parser-corpus",
                  "minimums": {
                    "reading_order_f1": 1.0,
                    "quote_anchor_accuracy": 1.0,
                    "bbox_coverage": 1.0
                  },
                  "cases": [
                    {
                      "name": "single-column-generated",
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        var corpus = ParserBenchmarkCorpus.load(manifest);

        assertThat(corpus.name()).isEqualTo("generated-parser-corpus");
        assertThat(corpus.cases()).hasSize(1);
        var result = corpus.evaluate().getFirst();
        assertThat(result.name()).isEqualTo("single-column-generated");
        assertThat(result.metric("reading_order_f1")).isEqualTo(1.0);
        corpus.requireMinimums();
    }

    @Test
    @DisplayName("human-labeled corpus manifests expose label metadata and require declared metric thresholds")
    void humanLabeledManifestRequiresMetadataAndMetricThresholds() throws Exception {
        var source = writePdf("PROFILE", "Experienced operator");
        var expected = expectedDocument("PROFILE\nExperienced operator");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        var manifest = writeManifest("""
                {
                  "name": "human-labeled-parser-corpus",
                  "kind": "human-labeled",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "requiredMetrics": [
                      "reading_order_f1",
                      "bbox_coverage",
                      "evidence_span_accuracy"
                    ]
                  },
                  "minimums": {
                    "reading_order_f1": 1.0,
                    "bbox_coverage": 1.0,
                    "evidence_span_accuracy": 1.0
                  },
                  "cases": [
                    {
                      "name": "human-labeled-single-column",
                      "labelId": "layout-v1-0001",
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        var corpus = ParserBenchmarkCorpus.load(manifest);

        assertThat(corpus.kind()).isEqualTo("human-labeled");
        assertThat(corpus.labelSetVersion()).contains("layout-v1");
        assertThat(corpus.requiredMetrics())
                .containsExactly("reading_order_f1", "bbox_coverage", "evidence_span_accuracy");
        corpus.requireThresholds();
    }

    @Test
    @DisplayName("human-labeled corpus manifests fail when required metrics have no thresholds")
    void humanLabeledManifestRejectsMissingRequiredMetricThresholds() throws Exception {
        var source = writePdf("PROFILE", "Experienced operator");
        var expected = expectedDocument("PROFILE\nExperienced operator");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        var manifest = writeManifest("""
                {
                  "name": "broken-human-labeled-parser-corpus",
                  "kind": "human-labeled",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "requiredMetrics": ["reading_order_f1", "bbox_iou"]
                  },
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "human-labeled-missing-threshold",
                      "labelId": "layout-v1-0002",
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("human-labeled")
                .hasMessageContaining("bbox_iou")
                .hasMessageContaining("minimums or maximums");
    }

    @Test
    @DisplayName("parser accuracy human-labeled corpus requires declared tag coverage")
    void parserAccuracyHumanLabeledManifestRequiresDeclaredCoverage() throws Exception {
        var source = writePdf("PROFILE", "Experienced operator");
        var expected = expectedDocument("PROFILE\nExperienced operator");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        var manifest = writeManifest("""
                {
                  "name": "parser-accuracy-corpus",
                  "kind": "human-labeled",
                  "qualityProfile": "parser-accuracy",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "reviewType": "human-reviewed",
                    "requiredMetrics": ["reading_order_f1"],
                    "requiredTags": ["multi-layout", "table", "ocr"],
                    "minCasesPerTag": 2,
                    "minTotalCases": 6
                  },
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "single-column-only",
                      "labelId": "layout-v1-0003",
                      "tags": ["single-column"],
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parser-accuracy")
                .hasMessageContaining("multi-layout")
                .hasMessageContaining("minimum=2")
                .hasMessageContaining("actual=0");
    }

    @Test
    @DisplayName("parser accuracy human-labeled corpus exposes profile and coverage metadata")
    void parserAccuracyHumanLabeledManifestExposesCoverageMetadata() throws Exception {
        var source = writePdf("PROFILE", "Experienced operator");
        var expected = expectedDocument("PROFILE\nExperienced operator");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        var manifest = writeManifest("""
                {
                  "name": "parser-accuracy-corpus",
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
                      "name": "multi-layout-case",
                      "labelId": "layout-v1-0004",
                      "tags": ["multi-layout", "table", "ocr", "bbox", "source-map"],
                      "source": "%s",
                      "sourceSha256": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source), sha256(source)));

        var corpus = ParserBenchmarkCorpus.load(manifest);

        assertThat(corpus.qualityProfile()).contains("parser-accuracy");
        assertThat(corpus.requiredTags()).containsExactly("multi-layout", "table", "ocr", "bbox", "source-map");
        assertThat(corpus.minCasesPerTag()).containsEntry("multi-layout", 1);
        assertThat(corpus.minCasesPerTag()).containsEntry("source-map", 1);
        assertThat(corpus.minTotalCases()).contains(1);
        assertThat(corpus.reviewType()).contains("human-reviewed");
        corpus.requireThresholds();
    }

    @Test
    @DisplayName("human-reviewed parser accuracy corpus requires the core metric set")
    void humanReviewedParserAccuracyCorpusRequiresCoreMetrics() throws Exception {
        var source = writePdf("PROFILE", "Experienced operator");
        var expected = expectedDocument("PROFILE\nExperienced operator");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        var manifest = writeManifest("""
                {
                  "name": "parser-accuracy-incomplete-metrics",
                  "kind": "human-labeled",
                  "qualityProfile": "parser-accuracy",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "reviewType": "human-reviewed",
                    "requiredMetrics": ["reading_order_f1"],
                    "requiredTags": ["multi-layout"],
                    "minCasesPerTag": 1,
                    "minTotalCases": 1
                  },
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "multi-layout-case",
                      "labelId": "layout-v1-0009",
                      "tags": ["multi-layout"],
                      "source": "%s",
                      "sourceSha256": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source), sha256(source)));

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("human-reviewed")
                .hasMessageContaining("requiredMetrics")
                .hasMessageContaining("bbox_iou")
                .hasMessageContaining("ocr_text_accuracy");
    }

    @Test
    @DisplayName("human-reviewed parser accuracy corpus requires core coverage tags")
    void humanReviewedParserAccuracyCorpusRequiresCoreTags() throws Exception {
        var source = writePdf("PROFILE", "Experienced operator");
        var expected = expectedDocument("PROFILE\nExperienced operator");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        var manifest = writeManifest("""
                {
                  "name": "parser-accuracy-incomplete-tags",
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
                    "requiredTags": ["multi-layout"],
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
                      "name": "multi-layout-case",
                      "labelId": "layout-v1-0010",
                      "tags": ["multi-layout"],
                      "source": "%s",
                      "sourceSha256": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source), sha256(source)));

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("human-reviewed")
                .hasMessageContaining("requiredTags")
                .hasMessageContaining("table")
                .hasMessageContaining("source-map");
    }

    @Test
    @DisplayName("parser accuracy human-labeled corpus requires explicit review type")
    void parserAccuracyHumanLabeledManifestRequiresReviewType() throws Exception {
        var source = writePdf("PROFILE", "Experienced operator");
        var expected = expectedDocument("PROFILE\nExperienced operator");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        var manifest = writeManifest("""
                {
                  "name": "parser-accuracy-missing-review-type",
                  "kind": "human-labeled",
                  "qualityProfile": "parser-accuracy",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "requiredMetrics": ["reading_order_f1"],
                    "requiredTags": ["multi-layout"],
                    "minCasesPerTag": 1,
                    "minTotalCases": 1
                  },
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "multi-layout-case",
                      "labelId": "layout-v1-0005",
                      "tags": ["multi-layout"],
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parser-accuracy")
                .hasMessageContaining("reviewType");
    }

    @Test
    @DisplayName("parser accuracy human-labeled cases require label ids and tags")
    void parserAccuracyHumanLabeledCasesRequireLabelIdsAndTags() throws Exception {
        var source = writePdf("PROFILE", "Experienced operator");
        var expected = expectedDocument("PROFILE\nExperienced operator");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        var missingLabel = writeManifest("""
                {
                  "name": "missing-case-label-corpus",
                  "kind": "human-labeled",
                  "qualityProfile": "parser-accuracy",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "reviewType": "human-reviewed",
                    "requiredMetrics": ["reading_order_f1"],
                    "requiredTags": ["multi-layout"],
                    "minCasesPerTag": 1,
                    "minTotalCases": 1
                  },
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "missing-label-id",
                      "tags": ["multi-layout"],
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(missingLabel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-label-id")
                .hasMessageContaining("labelId");

        var missingTags = writeManifest("""
                {
                  "name": "missing-case-tags-corpus",
                  "kind": "human-labeled",
                  "qualityProfile": "parser-accuracy",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "reviewType": "human-reviewed",
                    "requiredMetrics": ["reading_order_f1"],
                    "requiredTags": ["multi-layout"],
                    "minCasesPerTag": 1,
                    "minTotalCases": 1
                  },
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "missing-tags",
                      "labelId": "layout-v1-0005",
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(missingTags))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-tags")
                .hasMessageContaining("tags");
    }

    @Test
    @DisplayName("human-reviewed parser accuracy corpus requires minimum total case count")
    void humanReviewedParserAccuracyCorpusRequiresMinimumTotalCases() throws Exception {
        var source = writePdf("PROFILE", "Experienced operator");
        var expected = expectedDocument("PROFILE\nExperienced operator");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        var missingMinimum = writeManifest("""
                {
                  "name": "parser-accuracy-missing-total",
                  "kind": "human-labeled",
                  "qualityProfile": "parser-accuracy",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "reviewType": "human-reviewed",
                    "requiredMetrics": ["reading_order_f1"],
                    "requiredTags": ["multi-layout"],
                    "minCasesPerTag": 1
                  },
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "multi-layout-case",
                      "labelId": "layout-v1-0006",
                      "tags": ["multi-layout"],
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(missingMinimum))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("human-reviewed")
                .hasMessageContaining("minTotalCases");

        var tooSmall = writeManifest("""
                {
                  "name": "parser-accuracy-too-small",
                  "kind": "human-labeled",
                  "qualityProfile": "parser-accuracy",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "reviewType": "human-reviewed",
                    "requiredMetrics": ["reading_order_f1"],
                    "requiredTags": ["multi-layout"],
                    "minCasesPerTag": 1,
                    "minTotalCases": 2
                  },
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "multi-layout-case",
                      "labelId": "layout-v1-0007",
                      "tags": ["multi-layout"],
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(tooSmall))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minTotalCases")
                .hasMessageContaining("minimum=2")
                .hasMessageContaining("actual=1");
    }

    @Test
    @DisplayName("human-reviewed parser accuracy cases require source SHA-256 pins")
    void humanReviewedParserAccuracyCasesRequireSourceSha256() throws Exception {
        var source = writePdf("PROFILE", "Experienced operator");
        var expected = expectedDocument("PROFILE\nExperienced operator");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "PROFILE\nExperienced operator\n");
        var manifest = writeManifest("""
                {
                  "name": "parser-accuracy-unpinned-source",
                  "kind": "human-labeled",
                  "qualityProfile": "parser-accuracy",
                  "labeling": {
                    "labelSetVersion": "layout-v1",
                    "reviewedAt": "2026-06-13",
                    "reviewer": "fixture-reviewer",
                    "reviewType": "human-reviewed",
                    "requiredMetrics": ["reading_order_f1"],
                    "requiredTags": ["multi-layout"],
                    "minCasesPerTag": 1,
                    "minTotalCases": 1
                  },
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "multi-layout-case",
                      "labelId": "layout-v1-0008",
                      "tags": ["multi-layout"],
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("human-reviewed")
                .hasMessageContaining("sourceSha256")
                .hasMessageContaining("multi-layout-case");
    }

    @Test
    @DisplayName("corpus manifest can gate section boundary F1")
    void manifestCanGateSectionBoundaryF1() throws Exception {
        var source = writePdf("PROFILE", "Experienced operator", "WORK EXPERIENCE", "Production assistant");
        var expected = expectedDocument("PROFILE\nExperienced operator\nWORK EXPERIENCE\nProduction assistant");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(
                tempDir.resolve("expected.md"),
                "PROFILE\nExperienced operator\nWORK EXPERIENCE\nProduction assistant\n");
        var manifest = writeManifest("""
                {
                  "name": "section-boundary-corpus",
                  "minimums": {
                    "section_boundary_f1": 1.0,
                    "reading_order_f1": 1.0
                  },
                  "cases": [
                    {
                      "name": "generated-section-boundaries",
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        var corpus = ParserBenchmarkCorpus.load(manifest);

        assertThat(corpus.evaluate().getFirst().metric("section_boundary_f1")).isEqualTo(1.0);
        corpus.requireThresholds();
    }

    @Test
    @DisplayName("corpus manifest can gate evidence span accuracy")
    void manifestCanGateEvidenceSpanAccuracy() throws Exception {
        var source = writePdf("Work Experience", "Java Engineer");
        var expected = expectedDocument("Work Experience\nJava Engineer");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "Work Experience\nJava Engineer\n");
        var manifest = writeManifest("""
                {
                  "name": "evidence-span-corpus",
                  "minimums": {
                    "evidence_span_accuracy": 1.0,
                    "reading_order_f1": 1.0
                  },
                  "cases": [
                    {
                      "name": "generated-evidence-spans",
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        var corpus = ParserBenchmarkCorpus.load(manifest);

        assertThat(corpus.evaluate().getFirst().metric("evidence_span_accuracy"))
                .isEqualTo(1.0);
        corpus.requireThresholds();
    }

    @Test
    @DisplayName("corpus manifest can gate compact LLM reduction at corpus aggregate level")
    void manifestCanGateCompactLlmReductionAggregateMinimum() throws Exception {
        var source = writePdf("Work Experience", "Java Engineer");
        var expected = expectedDocument("Work Experience\nJava Engineer");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "Work Experience\nJava Engineer\n");
        var manifest = writeManifest("""
                {
                  "name": "compact-corpus",
                  "minimums": {
                    "reading_order_f1": 1.0,
                    "compact_llm_size_reduction_min": 1.0
                  },
                  "cases": [
                    {
                      "name": "generated-compact",
                      "source": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        var corpus = ParserBenchmarkCorpus.load(manifest);

        assertThat(corpus.aggregateMetrics()).containsKey("compact_llm_size_reduction_min");
        assertThatThrownBy(corpus::requireThresholds)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corpus compact_llm_size_reduction_min")
                .hasMessageContaining("minimum=1.0");
    }

    @Test
    @DisplayName("corpus manifest rejects cases without expected TrustDocument labels")
    void manifestRequiresExpectedDocumentLabels() throws Exception {
        var source = writePdf("Work Experience", "Java Engineer");
        Files.writeString(tempDir.resolve("expected.md"), "Work Experience\nJava Engineer\n");
        var manifest = writeManifest("""
                {
                  "name": "missing-label-corpus",
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "missing-label-case",
                      "source": "%s",
                      "expectedMarkdown": "expected.md"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-label-case")
                .hasMessageContaining("expectedDocument");
    }

    @Test
    @DisplayName("corpus manifest enforces maximum thresholds for lower-is-better metrics")
    void manifestEnforcesMaximumThresholds() throws Exception {
        var source = writePdf("Warning Fixture");
        var expected = expectedDocumentWithParserWarning("Warning Fixture", "layout_low_confidence");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "Warning Fixture\n");
        var manifest = writeManifest("""
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

        var corpus = ParserBenchmarkCorpus.load(manifest);

        assertThat(corpus.maximums()).containsEntry("strict_warning_false_negative_rate", 0.02);
        assertThat(corpus.evaluate().getFirst().metric("strict_warning_false_negative_rate"))
                .isEqualTo(1.0);
        assertThatThrownBy(corpus::requireThresholds)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-warning-case")
                .hasMessageContaining("strict_warning_false_negative_rate")
                .hasMessageContaining("maximum=0.02");
    }

    @Test
    @DisplayName("corpus manifest can request OCR preset for scanned PDF cases")
    void manifestCanRequestOcrPreset() throws Exception {
        var source = writeBlankPdf();
        var worker = writeFakeOcrWorker("OCR benchmark text", 0.96);
        var runtime = writeFakeOcrRuntime(worker, "OCR benchmark text", 0.96);
        var expected = expectedDocument("OCR benchmark text");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "OCR benchmark text\n");
        var manifest = writeManifest("""
                {
                  "name": "ocr-corpus",
                  "minimums": {"ocr_text_accuracy": 1.0},
                  "cases": [
                    {
                      "name": "scanned-ocr-generated",
                      "source": "%s",
                      "preset": "ocr",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source)));

        withSystemProperties(
                Map.of("doctruth.runtime.command", runtime.toString(), "doctruth.ocr.command", worker.toString()),
                () -> {
                    var corpus = ParserBenchmarkCorpus.load(manifest);

                    var document = corpus.cases().getFirst().document();
                    assertThat(document.parserRun().preset()).isEqualTo("ocr");
                    assertThat(document.body().units().getFirst().kind()).isEqualTo(TrustUnitKind.OCR_REGION);
                    assertThat(corpus.evaluate().getFirst().metric("ocr_text_accuracy"))
                            .isEqualTo(1.0);
                    corpus.requireMinimums();
                });
    }

    @Test
    @DisplayName("corpus manifest verifies local PDF fixture SHA-256 pins")
    void manifestVerifiesLocalPdfFixtureSha() throws Exception {
        var source = writePdf("Local Fixture", "Human Label");
        var expected = expectedDocument("Local Fixture\nHuman Label");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "Local Fixture\nHuman Label\n");
        var manifest = writeManifest("""
                {
                  "name": "local-sha-corpus",
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "local-pdf",
                      "source": "%s",
                      "sourceSha256": "sha256:%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(tempDir.relativize(source), "b".repeat(64)));

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local-pdf")
                .hasMessageContaining("SHA-256 mismatch");
    }

    @Test
    @DisplayName("corpus manifest downloads remote PDF fixtures with SHA-256 verification")
    void manifestCanUseRemotePdfFixturesWithShaVerification() throws Exception {
        var source = writePdf("Remote Fixture", "Human Label");
        var expected = expectedDocument("Remote Fixture\nHuman Label");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "Remote Fixture\nHuman Label\n");
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/remote.pdf", exchange -> {
            byte[] body = Files.readAllBytes(source);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var manifest = writeManifest("""
                    {
                      "name": "remote-real-pdf-corpus",
                      "minimums": {"reading_order_f1": 1.0},
                      "cases": [
                        {
                          "name": "remote-pdf",
                          "sourceUrl": "http://127.0.0.1:%d/remote.pdf",
                          "sourceSha256": "%s",
                          "expectedMarkdown": "expected.md",
                          "expectedDocument": "expected.json"
                        }
                      ]
                    }
                    """.formatted(server.getAddress().getPort(), sha256(source)));

            var corpus = ParserBenchmarkCorpus.load(manifest);

            assertThat(corpus.cases().getFirst().document().toMarkdownClean()).contains("Remote Fixture");
            corpus.requireMinimums();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("offline corpus manifest refuses uncached remote PDF fixtures before network access")
    void offlineManifestRefusesUncachedRemotePdfFixtures() throws Exception {
        var expected = expectedDocument("Remote Fixture\nHuman Label");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "Remote Fixture\nHuman Label\n");
        var manifest = writeManifest("""
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

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(manifest, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offline-remote-pdf")
                .hasMessageContaining("offline mode refuses remote benchmark source");
    }

    @Test
    @DisplayName("offline corpus manifest uses cached remote PDF fixtures after SHA-256 verification")
    void offlineManifestUsesCachedRemotePdfFixtures() throws Exception {
        var source = writePdf("Remote Fixture", "Human Label");
        String sha = sha256(source);
        var cache = tempDir.resolve(".doctruth-corpus-cache");
        Files.createDirectories(cache);
        Files.copy(source, cache.resolve("offline-cached-pdf-" + sha.replace("sha256:", "") + ".pdf"));
        var expected = expectedDocument("Remote Fixture\nHuman Label");
        Files.writeString(tempDir.resolve("expected.json"), expected.toJsonFull());
        Files.writeString(tempDir.resolve("expected.md"), "Remote Fixture\nHuman Label\n");
        var manifest = writeManifest("""
                {
                  "name": "offline-cached-corpus",
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "offline-cached-pdf",
                      "sourceUrl": "http://127.0.0.1:1/remote.pdf",
                      "sourceSha256": "%s",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """.formatted(sha));

        var corpus = ParserBenchmarkCorpus.load(manifest, true);

        assertThat(corpus.cases().getFirst().document().toMarkdownClean()).contains("Remote Fixture");
        corpus.requireMinimums();
    }

    @Test
    @DisplayName("corpus manifest reports missing fixture paths with case context")
    void manifestReportsMissingFixturePaths() throws Exception {
        var manifest = writeManifest("""
                {
                  "name": "broken-corpus",
                  "minimums": {"reading_order_f1": 1.0},
                  "cases": [
                    {
                      "name": "missing-source-case",
                      "source": "missing.pdf",
                      "expectedMarkdown": "expected.md",
                      "expectedDocument": "expected.json"
                    }
                  ]
                }
                """);

        assertThatThrownBy(() -> ParserBenchmarkCorpus.load(manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-source-case")
                .hasMessageContaining("missing.pdf");
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(path));
        var builder = new StringBuilder("sha256:");
        for (byte b : hash) {
            builder.append("%02x".formatted(b));
        }
        return builder.toString();
    }

    private Path writeManifest(String json) throws IOException {
        var path = tempDir.resolve("corpus.json");
        Files.writeString(path, json);
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
