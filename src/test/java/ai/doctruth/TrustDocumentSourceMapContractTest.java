package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract tests for rendered outputs that carry source maps. */
class TrustDocumentSourceMapContractTest {

    private static final DocumentMetadata META = new DocumentMetadata("resume.pdf", 1, Optional.empty());
    private static final ParserRun PARSER_RUN = new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of());

    @Test
    @DisplayName("markdown_with_source_map maps rendered offsets back to unit and evidence ids")
    void markdownWithSourceMapPreservesRenderedOffsets() {
        var doc = TrustDocument.fromParsed(
                new ParsedDocument(
                        "doc-1",
                        List.of(section("Work Experience", 1), section("Acme Logistics Supervisor", 2)),
                        META),
                "sha256:source",
                PARSER_RUN);

        TrustRenderedDocument rendered = doc.toMarkdownWithSourceMap();

        assertThat(rendered.format()).isEqualTo("markdown");
        assertThat(rendered.text()).contains("Work Experience");
        assertThat(rendered.sourceHash()).isEqualTo("sha256:source");
        assertThat(rendered.contentHash()).isEqualTo(sha256(rendered.text()));
        assertThat(rendered.sourceMap()).hasSize(2);
        assertThat(rendered.sourceMap().getFirst().unitId()).isEqualTo("unit-0001");
        assertThat(rendered.sourceMap().getFirst().evidenceSpanIds()).containsExactly("span-0001");
        assertThat(rendered.text().substring(
                        rendered.sourceMap().get(1).startOffset(), rendered.sourceMap().get(1).endOffset()))
                .isEqualTo("Acme Logistics Supervisor");
    }

    @Test
    @DisplayName("markdown_with_source_map renders tables as GFM and maps every cell")
    void markdownWithSourceMapRendersTablesAsGfmAndMapsCells() {
        var table = new TableSection(List.of(List.of("Company", "Role"), List.of("Acme", "Engineer")), loc(1));
        var doc = TrustDocument.fromParsed(
                new ParsedDocument("doc-1", List.of(table), META),
                "sha256:source",
                PARSER_RUN);

        TrustRenderedDocument rendered = doc.toMarkdownWithSourceMap();

        assertThat(rendered.text()).isEqualTo("| Company | Role |\n| --- | --- |\n| Acme | Engineer |\n");
        assertThat(rendered.sourceMap()).hasSize(4);
        assertThat(rendered.sourceMap()).extracting(TrustSourceMapEntry::unitId)
                .containsExactly("unit-0001", "unit-0002", "unit-0003", "unit-0004");
        assertThat(rendered.sourceMap()).allSatisfy(entry -> assertThat(rendered.text()
                        .substring(entry.startOffset(), entry.endOffset()))
                .isNotBlank()
                .doesNotContain("|"));
    }

    @Test
    @DisplayName("compact_llm_with_source_map maps compact text fields back to unit and evidence ids")
    void compactLlmWithSourceMapPreservesRenderedOffsets() {
        var doc = TrustDocument.fromParsed(
                new ParsedDocument(
                        "doc-1",
                        List.of(sectionWithBbox("Work Experience", 1), section("Acme Logistics Supervisor", 2)),
                        META),
                "sha256:source",
                PARSER_RUN);

        TrustRenderedDocument rendered = doc.toCompactLlmWithSourceMap();

        assertThat(rendered.format()).isEqualTo("compact_llm");
        assertThat(rendered.text()).isEqualTo(doc.toCompactLlm());
        assertThat(rendered.contentHash()).isEqualTo(sha256(rendered.text()));
        assertThat(rendered.sourceMap()).hasSize(2);
        assertThat(rendered.sourceMap().getFirst().unitId()).isEqualTo("unit-0001");
        assertThat(rendered.text().substring(
                        rendered.sourceMap().getFirst().startOffset(),
                        rendered.sourceMap().getFirst().endOffset()))
                .isEqualTo("Work Experience");
        assertThat(rendered.sourceMap().getFirst().evidenceSpanIds()).containsExactly("span-0001");
    }

    @Test
    @DisplayName("review html carries stable unit anchors and evidence metadata")
    void reviewHtmlCarriesStableAnchors() {
        var doc = TrustDocument.fromParsed(
                new ParsedDocument("doc-1", List.of(sectionWithBbox("Work Experience", 1)), META),
                "sha256:source",
                PARSER_RUN);

        String html = doc.toHtmlReview();

        assertThat(html).contains("data-trust-unit-id=\"unit-0001\"");
        assertThat(html).contains("data-evidence-span-ids=\"span-0001\"");
        assertThat(html).contains("data-bbox=\"100,120,500,240\"");
        assertThat(html).contains("data-bbox-space=\"normalized-0-1000\"");
        assertThat(html).contains("Work Experience");
    }

    @Test
    @DisplayName("review html carries table and cell anchors with bbox metadata")
    void reviewHtmlCarriesTableAndCellAnchors() {
        var table = new TableSection(
                List.of(List.of("Company", "Role"), List.of("Acme", "Engineer")),
                loc(1),
                Optional.of(new BoundingBox(80, 200, 720, 420)),
                List.of(
                        new TableCellRegion(0, 0, new BoundingBox(80, 200, 400, 300)),
                        new TableCellRegion(0, 1, new BoundingBox(400, 200, 720, 300)),
                        new TableCellRegion(1, 0, new BoundingBox(80, 300, 400, 420)),
                        new TableCellRegion(1, 1, new BoundingBox(400, 300, 720, 420))));
        var doc = TrustDocument.fromParsed(
                new ParsedDocument("doc-1", List.of(table), META),
                "sha256:source",
                PARSER_RUN);

        String html = doc.toHtmlReview();

        assertThat(html).contains("<table data-trust-table-id=\"table-0001\"");
        assertThat(html).contains("data-bbox=\"80,200,720,420\"");
        assertThat(html).contains("data-trust-cell-id=\"cell-0001-0000-0000\"");
        assertThat(html).contains("data-trust-unit-id=\"unit-0001\"");
        assertThat(html).contains("data-bbox=\"80,200,400,300\"");
        assertThat(html).contains("<td").contains("Company").contains("</td>");
    }

    @Test
    @DisplayName("review html renders page surfaces with page metadata for overlays")
    void reviewHtmlRendersPageSurfacesForOverlays() {
        var unit = new TrustUnit(
                "unit-0001",
                TrustUnitKind.TEXT_BLOCK,
                new TrustUnitLocation(2, Optional.of(new BoundingBox(100, 120, 500, 240)), 1),
                new TrustUnitContent("Second page finding", "section-0001"),
                new TrustUnitEvidence(List.of("span-0001"), new Confidence(0.98, "fixture"), List.of()));
        var doc = new TrustDocument(
                "doc-1",
                new TrustDocumentSource("resume.pdf", "sha256:source", new DocumentMetadata("resume.pdf", 2, Optional.empty())),
                new TrustDocumentBody(
                        List.of(
                                new TrustPage(1, 1000, 1000, true, "sha256:page1"),
                                new TrustPage(2, 1000, 1400, true, "sha256:page2")),
                        List.of(unit),
                        List.of()),
                PARSER_RUN,
                AuditGradeStatus.AUDIT_GRADE);

        String html = doc.toHtmlReview();

        assertThat(html)
                .contains("<section data-trust-page-number=\"2\"")
                .contains("data-page-width=\"1000\"")
                .contains("data-page-height=\"1400\"")
                .contains("data-text-layer-available=\"true\"")
                .contains("data-image-hash=\"sha256:page2\"");
        assertThat(html.indexOf("data-trust-page-number=\"2\""))
                .isLessThan(html.indexOf("data-trust-unit-id=\"unit-0001\""));
    }

    @Test
    @DisplayName("review html renders visual bbox overlay layer for units, tables, and cells")
    void reviewHtmlRendersVisualBboxOverlayLayer() {
        var table = new TableSection(
                List.of(List.of("Company", "Role"), List.of("Acme", "Engineer")),
                loc(1),
                Optional.of(new BoundingBox(80, 200, 720, 420)),
                List.of(new TableCellRegion(0, 0, new BoundingBox(80, 200, 400, 300))));
        var doc = TrustDocument.fromParsed(
                new ParsedDocument("doc-1", List.of(sectionWithBbox("Work Experience", 1), table), META),
                "sha256:source",
                PARSER_RUN);

        String html = doc.toHtmlReview();

        assertThat(html)
                .contains("data-trust-overlay-layer=\"bbox\"")
                .contains("data-trust-bbox-overlay=\"unit\"")
                .contains("data-trust-overlay-for=\"unit-0001\"")
                .contains("style=\"left:10%;top:12%;width:40%;height:12%;\"")
                .contains("data-trust-bbox-overlay=\"table\"")
                .contains("data-trust-overlay-for=\"table-0001\"")
                .contains("style=\"left:8%;top:20%;width:64%;height:22%;\"")
                .contains("data-trust-bbox-overlay=\"cell\"")
                .contains("data-trust-overlay-for=\"cell-0001-0000-0000\"");
    }

    private static TextSection section(String text, int line) {
        return new TextSection(
                text,
                loc(line),
                BlockKind.BODY,
                Optional.empty());
    }

    private static TextSection sectionWithBbox(String text, int line) {
        return new TextSection(
                text,
                loc(line),
                BlockKind.BODY,
                Optional.of(new BoundingBox(100, 120, 500, 240)));
    }

    private static SourceLocation loc(int line) {
        return new SourceLocation(1, 1, line, line, line * 100);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
