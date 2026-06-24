package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract tests for adapting the existing Java parser output into v1 trust contracts. */
class TrustDocumentAdapterTest {

    private static final DocumentMetadata META = new DocumentMetadata("resume.pdf", 2, Optional.empty());
    private static final ParserRun PARSER_RUN = new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of());
    private static final SourceLocation LOC = new SourceLocation(1, 1, 1, 1, 0);
    private static final BoundingBox BOX = new BoundingBox(100, 100, 500, 200);

    @Test
    @DisplayName("adapts ParsedDocument sections into TrustDocument pages and units")
    void adaptsParsedDocumentToTrustDocument() {
        var parsed = new ParsedDocument(
                "doc-1",
                List.of(
                        new TextSection("Work Experience", LOC, BlockKind.HEADING, Optional.of(BOX)),
                        new FigureSection("Architecture diagram", LOC, Optional.of(BOX))),
                META);

        var doc = TrustDocument.fromParsed(parsed, "sha256:source", PARSER_RUN);

        assertThat(doc.docId()).isEqualTo("doc-1");
        assertThat(doc.source().sourceFilename()).isEqualTo("resume.pdf");
        assertThat(doc.source().sourceHash()).isEqualTo("sha256:source");
        assertThat(doc.body().pages()).extracting(TrustPage::pageNumber).containsExactly(1, 2);
        assertThat(doc.body().units()).hasSize(2);
        assertThat(doc.body().units().get(0).kind()).isEqualTo(TrustUnitKind.HEADING);
        assertThat(doc.body().units().get(0).location().boundingBox()).contains(BOX);
        assertThat(doc.body().units().get(0).evidence().evidenceSpanIds()).containsExactly("span-0001");
        assertThat(doc.body().units().get(1).kind()).isEqualTo(TrustUnitKind.FIGURE_CAPTION);
        assertThat(doc.body().units().get(1).location().boundingBox()).contains(BOX);
        assertThat(doc.auditGradeStatus()).isEqualTo(AuditGradeStatus.UNKNOWN);
    }

    @Test
    @DisplayName("adapts TableSection into structured TrustTable and table-cell units")
    void adaptsTableSectionToStructuredTable() {
        var table = new TableSection(List.of(List.of("Company", "Role"), List.of("Acme", "Engineer")), LOC);
        var parsed = new ParsedDocument("doc-1", List.of(table), META);

        var doc = TrustDocument.fromParsed(parsed, "sha256:source", PARSER_RUN);

        assertThat(doc.body().tables()).hasSize(1);
        assertThat(doc.body().tables().getFirst().cells()).hasSize(4);
        assertThat(doc.body().tables().getFirst().cells().getFirst().text()).isEqualTo("Company");
        assertThat(doc.body().units()).extracting(TrustUnit::kind).containsOnly(TrustUnitKind.TABLE_CELL);
        assertThat(doc.body().units()).extracting(unit -> unit.content().text())
                .containsExactly("Company", "Role", "Acme", "Engineer");
    }

    @Test
    @DisplayName("rejects blank source hash and null parser run")
    void rejectsInvalidAdapterInputs() {
        var parsed = new ParsedDocument("doc-1", List.of(), META);

        assertThatThrownBy(() -> TrustDocument.fromParsed(parsed, " ", PARSER_RUN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceHash");
        assertThatThrownBy(() -> TrustDocument.fromParsed(parsed, "sha256:source", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("parserRun");
    }
}
