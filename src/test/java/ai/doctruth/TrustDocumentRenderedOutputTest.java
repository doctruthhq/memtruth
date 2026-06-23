package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

        assertThat(compact).contains("u|unit-0001|TEXT_BLOCK|p1|span-0001|Work Experience|bbox=100,100,500,200");
    }

    @Test
    @DisplayName("runtime layered party headings render as text content blocks")
    void runtimeLayeredPartyHeadingsRenderAsTextContentBlocks() throws Exception {
        var doc = layeredDocument(runtimeParserRun(), "Party A: Acme Industrial Materials Pty Ltd");

        assertThat(contentBlockType(doc)).isEqualTo("text");
    }

    @Test
    @DisplayName("runtime layered contract headings remain headings")
    void runtimeLayeredContractHeadingsRemainHeadings() throws Exception {
        var doc = layeredDocument(runtimeParserRun(), "Contract: Commercial Terms");

        assertThat(contentBlockType(doc)).isEqualTo("heading");
    }

    @Test
    @DisplayName("runtime layered party obligation headings remain headings")
    void runtimeLayeredPartyObligationHeadingsRemainHeadings() throws Exception {
        var doc = layeredDocument(runtimeParserRun(), "Party A: Obligations");

        assertThat(contentBlockType(doc)).isEqualTo("heading");
    }

    @Test
    @DisplayName("oracle layered headings are not normalized during rendering")
    void oracleLayeredHeadingsAreNotNormalizedDuringRendering() throws Exception {
        var doc = layeredDocument(oracleParserRun(), "Party A: Acme Industrial Materials Pty Ltd");

        assertThat(contentBlockType(doc)).isEqualTo("heading");
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

    private static TrustDocument layeredDocument(ParserRun parserRun, String blockText) throws IOException {
        var parsed = new ParsedDocument(
                "doc-layered",
                List.of(new TextSection(blockText, LOC, BlockKind.BODY, Optional.of(BOX))),
                META);
        return TrustDocument.fromParsed(parsed, "sha256:source", parserRun)
                .withLayeredOutputs(layeredContentBlocks(blockText), MAPPER.createObjectNode());
    }

    private static com.fasterxml.jackson.databind.JsonNode layeredContentBlocks(String text) {
        var blocks = MAPPER.createArrayNode();
        var block = MAPPER.createObjectNode();
        block.put("blockId", "runtime-block-0001");
        block.put("type", "heading");
        block.put("page", 1);
        block.put("readingOrder", 1);
        block.put("text", text);
        block.set("sourceUnitIds", MAPPER.valueToTree(List.of("unit-0001")));
        block.set("evidenceSpanIds", MAPPER.valueToTree(List.of("span-0001")));
        block.set("warnings", MAPPER.createArrayNode());
        blocks.add(block);
        return blocks;
    }

    private static String contentBlockType(TrustDocument doc) throws IOException {
        var out = new StringWriter();
        doc.writeContentBlocks(out);
        return MAPPER.readTree(out.toString()).path("contentBlocks").get(0).path("type").asText();
    }

    private static ParserRun runtimeParserRun() {
        return new ParserRun("parser-run-runtime", "runtime-test", "lite", "rust-sidecar", List.of(), List.of());
    }

    private static ParserRun oracleParserRun() {
        return new ParserRun(
                "parser-run-opendataloader-hybrid-oracle",
                "opendataloader-hybrid-oracle",
                "benchmark-oracle",
                "opendataloader-hybrid-oracle",
                List.of(),
                List.of(),
                Map.of(),
                1L);
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
