package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract tests for table evidence emitted into TrustDocument. */
class TableExtractionContractTest {

    private static final DocumentMetadata META = new DocumentMetadata("resume.pdf", 1, Optional.empty());
    private static final ParserRun PARSER_RUN = new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of());
    private static final SourceLocation LOC = new SourceLocation(1, 1, 1, 3, 0);

    @Test
    @DisplayName("table cells keep row/column ranges and cell-backed unit source ids")
    void tableCellsKeepRangesAndSourceIds() {
        var parsed = new ParsedDocument(
                "doc-1",
                List.of(new TableSection(List.of(List.of("Company", "Role"), List.of("Acme", "Engineer")), LOC)),
                META);

        var doc = TrustDocument.fromParsed(parsed, "sha256:source", PARSER_RUN);

        var table = doc.body().tables().getFirst();
        assertThat(table.cells()).extracting(TrustTableCell::cellId)
                .containsExactly("cell-0001-0000-0000", "cell-0001-0000-0001", "cell-0001-0001-0000",
                        "cell-0001-0001-0001");
        assertThat(table.cells().get(3).rowRange()).isEqualTo(new TrustCellRange(1, 1));
        assertThat(table.cells().get(3).columnRange()).isEqualTo(new TrustCellRange(1, 1));
        assertThat(doc.body().units().get(3).content().sourceObjectId()).isEqualTo("cell-0001-0001-0001");
        assertThat(doc.body().units().get(3).evidence().evidenceSpanIds()).containsExactly("span-0004");
    }
}
