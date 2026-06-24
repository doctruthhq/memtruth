package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import ai.doctruth.spi.SignatureProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for v1 rendered output profiles. */
class TrustDocumentRenderedOutputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DocumentMetadata META = new DocumentMetadata("resume.pdf", 1, Optional.empty());
    private static final ParserRun PARSER_RUN = new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of());
    private static final SourceLocation LOC = new SourceLocation(1, 1, 1, 1, 0);
    private static final BoundingBox BOX = new BoundingBox(100, 100, 500, 200);

    @Test
    @DisplayName("json_full preserves canonical TrustDocument fields")
    void jsonFullPreservesCanonicalFields() {
        var doc = sampleDocument();

        String json = doc.toJsonFull();

        assertThat(json).contains("\"docId\":\"doc-1\"");
        assertThat(json).contains("\"sourceHash\":\"sha256:source\"");
        assertThat(json).contains("\"pages\"");
        assertThat(json).contains("\"units\"");
        assertThat(json).contains("\"parserRun\"");
        assertThat(json).contains("\"auditGradeStatus\":\"UNKNOWN\"");
    }

    @Test
    @DisplayName("json_evidence preserves evidence ids without full source metadata")
    void jsonEvidenceIsCompactEvidenceView() {
        var doc = sampleDocument();

        String evidence = doc.toJsonEvidence();

        assertThat(evidence).contains("\"docId\":\"doc-1\"");
        assertThat(evidence).contains("\"evidenceSpanIds\":[\"span-0001\"]");
        assertThat(evidence).contains("\"sourceHash\":\"sha256:source\"");
        assertThat(evidence).doesNotContain("\"sourcePublishedAt\"");
    }

    @Test
    @DisplayName("markdown_clean is readable and does not leak evidence metadata")
    void markdownCleanIsConsumptionView() {
        var doc = sampleDocument();

        String markdown = doc.toMarkdownClean();

        assertThat(markdown).contains("Work Experience");
        assertThat(markdown).contains("| Company | Role |\n| --- | --- |\n| Acme | Engineer |");
        assertThat(markdown).doesNotContain("span-0001");
        assertThat(markdown).doesNotContain("bbox");
        assertThat(markdown).doesNotContain("sha256");
    }

    @Test
    @DisplayName("markdown_clean preserves code/link blocks and escapes GFM-sensitive table cells")
    void markdownCleanPreservesCodeLinksAndEscapedTableCells() {
        var parsed = new ParsedDocument(
                "doc-gfm",
                List.of(
                        new TextSection(
                                """
                                ```java
                                System.out.println("ok");
                                ```
                                """,
                                LOC,
                                BlockKind.BODY,
                                Optional.of(BOX)),
                        new TextSection("[Spec](https://example.com/spec)", LOC, BlockKind.BODY, Optional.of(BOX)),
                        new TableSection(
                                List.of(List.of("Skill [A]", "Notes"), List.of("Uses a | b", "Backslash \\\\ ok")),
                                LOC)),
                META);
        var doc = TrustDocument.fromParsed(parsed, "sha256:gfm", PARSER_RUN);

        String markdown = doc.toMarkdownClean();

        assertThat(markdown)
                .contains("```java\nSystem.out.println(\"ok\");\n```")
                .contains("[Spec](https://example.com/spec)")
                .contains("| Skill \\[A\\] | Notes |")
                .contains("| Uses a \\| b | Backslash \\\\\\\\ ok |");
    }

    @Test
    @DisplayName("markdown_clean renders tables at their source reading position")
    void markdownCleanRendersTablesInlineWithSurroundingText() {
        var parsed = new ParsedDocument(
                "doc-inline-table",
                List.of(
                        new TextSection("Before table", LOC, BlockKind.BODY, Optional.of(BOX)),
                        new TableSection(List.of(List.of("Name", "Score"), List.of("Alex", "98")), LOC),
                        new TextSection("After table", LOC, BlockKind.BODY, Optional.of(BOX))),
                META);
        var doc = TrustDocument.fromParsed(parsed, "sha256:inline", PARSER_RUN);

        assertThat(doc.toMarkdownClean()).isEqualTo("""
                Before table

                | Name | Score |
                | --- | --- |
                | Alex | 98 |

                After table
                """);
    }

    @Test
    @DisplayName("markdown_clean renders runtime table-id source units inline")
    void markdownCleanRendersRuntimeTableIdSourceUnitsInline() {
        var table = new TrustTable(
                "table-0001",
                1,
                Optional.empty(),
                new Confidence(1.0, "runtime table"),
                List.of(
                        new TrustTableCell(
                                "cell-0001-0000-0000",
                                new TrustCellRange(0, 0),
                                new TrustCellRange(0, 0),
                                Optional.empty(),
                                "Name"),
                        new TrustTableCell(
                                "cell-0001-0001-0000",
                                new TrustCellRange(1, 1),
                                new TrustCellRange(0, 0),
                                Optional.empty(),
                                "Alex")));
        var units = List.of(
                trustUnit(1, TrustUnitKind.TEXT_BLOCK, "Before table", "section-0001"),
                trustUnit(2, TrustUnitKind.TABLE_CELL, "Name", "table-0001"),
                trustUnit(3, TrustUnitKind.TABLE_CELL, "Alex", "table-0001"),
                trustUnit(4, TrustUnitKind.TEXT_BLOCK, "After table", "section-0004"));
        var doc = new TrustDocument(
                "doc-runtime-table",
                new TrustDocumentSource("runtime.pdf", "sha256:runtime", META),
                new TrustDocumentBody(List.of(new TrustPage(1, 1000, 1000, true, "")), units, List.of(table)),
                PARSER_RUN,
                AuditGradeStatus.UNKNOWN);

        assertThat(doc.toMarkdownClean()).isEqualTo("""
                Before table

                | Name |
                | --- |
                | Alex |

                After table
                """);
    }

    @Test
    @DisplayName("plain text preserves content without markdown or evidence syntax")
    void plainTextIsCleanConsumptionView() {
        var doc = sampleDocument();

        String plain = doc.toPlainText();

        assertThat(plain)
                .contains("Work Experience")
                .contains("Company\tRole\nAcme\tEngineer")
                .doesNotContain("| --- |")
                .doesNotContain("span-0001")
                .doesNotContain("bbox")
                .doesNotContain("sha256");
    }

    @Test
    @DisplayName("content_blocks preserves heading block type")
    void contentBlocksPreserveHeadingBlockType() throws Exception {
        var doc = sampleDocument();
        var out = new java.io.StringWriter();

        doc.writeContentBlocks(out);

        var root = MAPPER.readTree(out.toString());
        var firstBlock = root.path("contentBlocks").get(0);
        assertThat(firstBlock.path("type").asText()).isEqualTo("heading");
        assertThat(firstBlock.path("text").asText()).isEqualTo("Work Experience");
    }

    @Test
    @DisplayName("content_blocks renders figure caption units as caption blocks")
    void contentBlocksRenderFigureCaptionsAsCaptionBlocks() throws Exception {
        var parsed = new ParsedDocument(
                "doc-caption",
                List.of(new FigureSection("Figure 1. Revenue trend", LOC)),
                META);
        var doc = TrustDocument.fromParsed(parsed, "sha256:caption", PARSER_RUN);
        var out = new java.io.StringWriter();

        doc.writeContentBlocks(out);

        var firstBlock = MAPPER.readTree(out.toString()).path("contentBlocks").get(0);
        assertThat(firstBlock.path("type").asText()).isEqualTo("caption");
        assertThat(firstBlock.path("text").asText()).isEqualTo("Figure 1. Revenue trend");
    }

    @Test
    @DisplayName("markdown_anchored includes bbox metadata when available")
    void markdownAnchoredIncludesBboxMetadata() {
        var doc = sampleDocument();

        String markdown = doc.toMarkdownAnchored();

        assertThat(markdown)
                .contains("{#ev:span-0001 page=1 bbox=\"100,100,500,200\"}")
                .contains("Work Experience");
    }

    @Test
    @DisplayName("compact_llm is deterministic, evidence-bearing, and smaller than json_full")
    void compactLlmIsDeterministicAndSmallerThanJsonFull() {
        var doc = sampleDocument();

        String compact = doc.toCompactLlm();

        assertThat(compact).isEqualTo(doc.toCompactLlm());
        assertThat(compact).contains("doc-1");
        assertThat(compact).contains("span-0001");
        assertThat(compact).contains("Work Experience");
        assertThat(compact.length()).isLessThan(doc.toJsonFull().length() * 3 / 4);
    }

    @Test
    @DisplayName("compact_llm preserves bbox metadata for citeable units")
    void compactLlmPreservesBboxMetadataForCiteableUnits() {
        var doc = sampleDocument();

        String compact = doc.toCompactLlm();

        assertThat(compact).contains("u|unit-0001|HEADING|p1|span-0001|Work Experience|bbox=100,100,500,200");
    }

    @Test
    @DisplayName("compact_llm preserves table ids and parser/unit warnings")
    void compactLlmPreservesTableIdsAndWarnings() {
        var doc = documentWithWarnings();

        String compact = doc.toCompactLlm();

        assertThat(compact.lines()).contains(
                "t|table-0001|p1|rows=2|cols=2",
                "w|parser|WARNING|layout_fallback|layout model unavailable",
                "w|unit-0001|WARNING|low_confidence_anchor|bbox was estimated");
        assertThat(compact).contains("u|unit-0002|TABLE_CELL|p1|span-0002|Company");
        assertThat(compact).contains("u|unit-0005|TABLE_CELL|p1|span-0005|Engineer");
    }

    @Test
    @DisplayName("markdown_review includes parser and unit warnings")
    void markdownReviewIncludesParserAndUnitWarnings() {
        var doc = documentWithWarnings();

        String review = doc.toMarkdownReview();

        assertThat(review)
                .contains("WARNING layout_fallback: layout model unavailable")
                .contains("unit-0001 WARNING low_confidence_anchor: bbox was estimated");
    }

    @Test
    @DisplayName("audit JSON carries canonical and evidence hashes for replay package integrity")
    void auditJsonCarriesPackageHashes() throws Exception {
        var doc = sampleDocument().withEvaluatedAuditGrade();

        var audit = MAPPER.readTree(doc.toAuditJson());

        assertThat(audit.path("canonicalHash").asText()).isEqualTo(doc.canonicalHash());
        assertThat(audit.path("evidenceHash").asText()).startsWith("sha256:");
        assertThat(audit.path("evidenceHash").asText()).isNotEqualTo("sha256:");
    }

    @Test
    @DisplayName("audit JSON can be signed through the shared SignatureProvider contract")
    void auditJsonCanBeSignedWithSharedSignatureProvider() {
        var doc = sampleDocument().withEvaluatedAuditGrade();
        SignatureProvider signer = auditJson -> "signed:" + auditJson;

        assertThat(doc.toAuditJson(SignatureProvider.IDENTITY)).isEqualTo(doc.toAuditJson());
        assertThat(doc.toAuditJson(signer)).isEqualTo("signed:" + doc.toAuditJson());
    }

    @Test
    @DisplayName("signed audit JSON can be written to a package file")
    void signedAuditJsonCanBeWrittenToFile(@TempDir Path dir) throws IOException {
        var doc = sampleDocument().withEvaluatedAuditGrade();
        var path = dir.resolve("packages/audit.json");

        doc.toAuditJson(path, auditJson -> "signed:" + auditJson);

        assertThat(Files.readString(path)).isEqualTo("signed:" + doc.toAuditJson());
    }

    @Test
    @DisplayName("signed audit JSON rejects null signer and path")
    void signedAuditJsonRejectsNullInputs(@TempDir Path dir) {
        var doc = sampleDocument().withEvaluatedAuditGrade();

        assertThatNullPointerException()
                .isThrownBy(() -> doc.toAuditJson((SignatureProvider) null))
                .withMessageContaining("signer");
        assertThatNullPointerException()
                .isThrownBy(() -> doc.toAuditJson(dir.resolve("audit.json"), null))
                .withMessageContaining("signer");
        assertThatNullPointerException()
                .isThrownBy(() -> doc.toAuditJson(null, SignatureProvider.IDENTITY))
                .withMessageContaining("path");
    }

    private static TrustDocument sampleDocument() {
        var parsed = new ParsedDocument(
                "doc-1",
                List.of(
                        new TextSection("Work Experience", LOC, BlockKind.HEADING, Optional.of(BOX)),
                        new TableSection(List.of(List.of("Company", "Role"), List.of("Acme", "Engineer")), LOC)),
                META);
        return TrustDocument.fromParsed(parsed, "sha256:source", PARSER_RUN);
    }

    private static TrustUnit trustUnit(int index, TrustUnitKind kind, String text, String sourceObjectId) {
        return new TrustUnit(
                "unit-%04d".formatted(index),
                kind,
                new TrustUnitLocation(1, Optional.of(BOX), index),
                new TrustUnitContent(text, sourceObjectId),
                new TrustUnitEvidence(
                        List.of("span-%04d".formatted(index)),
                        new Confidence(1.0, "test unit"),
                        List.of()));
    }

    private static TrustDocument documentWithWarnings() {
        var base = sampleDocument();
        var warning = new ParserWarning("low_confidence_anchor", ParserWarningSeverity.WARNING, "bbox was estimated");
        var warnedFirst = new TrustUnit(
                base.body().units().getFirst().unitId(),
                base.body().units().getFirst().kind(),
                base.body().units().getFirst().location(),
                base.body().units().getFirst().content(),
                new TrustUnitEvidence(
                        base.body().units().getFirst().evidence().evidenceSpanIds(),
                        base.body().units().getFirst().evidence().confidence(),
                        List.of(warning)));
        var units = new java.util.ArrayList<>(base.body().units());
        units.set(0, warnedFirst);
        var parserRun = new ParserRun(
                "1.0.0",
                "standard",
                "pdfbox",
                List.of(),
                List.of(new ParserWarning(
                        "layout_fallback", ParserWarningSeverity.WARNING, "layout model unavailable")));
        return new TrustDocument(
                base.docId(),
                base.source(),
                new TrustDocumentBody(base.body().pages(), units, base.body().tables()),
                parserRun,
                base.auditGradeStatus());
    }
}
