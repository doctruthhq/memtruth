package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfMergedTableExtractionTest {

    @TempDir
    Path tempDir;

    @Test
    void borderedTablePreservesHorizontalMergedCellColumnSpan() throws Exception {
        var document = parsePdfBox(writeMergedCellTablePdf());

        assertThat(document.body().tables()).hasSize(1);
        var table = document.body().tables().getFirst();
        assertThat(table.cells()).extracting(TrustTableCell::text)
                .containsExactly("Header", "A", "B");
        assertThat(table.cells().get(0).rowRange()).isEqualTo(new TrustCellRange(0, 0));
        assertThat(table.cells().get(0).columnRange()).isEqualTo(new TrustCellRange(0, 1));
        assertThat(table.cells().get(1).columnRange()).isEqualTo(new TrustCellRange(0, 0));
        assertThat(table.cells().get(2).columnRange()).isEqualTo(new TrustCellRange(1, 1));
        assertThat(table.cells()).allSatisfy(cell -> assertThat(cell.boundingBox()).isPresent());
        assertThat(document.body().units())
                .filteredOn(unit -> unit.kind() == TrustUnitKind.TABLE_CELL)
                .hasSize(3);
    }

    @Test
    void borderedTablePreservesVerticalMergedCellRowSpan() throws Exception {
        var document = parsePdfBox(writeRowSpanTablePdf());

        assertThat(document.body().tables()).hasSize(1);
        var table = document.body().tables().getFirst();
        assertThat(table.cells()).extracting(TrustTableCell::text)
                .containsExactly("Role", "Top", "Bottom");
        assertThat(table.cells().get(0).rowRange()).isEqualTo(new TrustCellRange(0, 1));
        assertThat(table.cells().get(0).columnRange()).isEqualTo(new TrustCellRange(0, 0));
        assertThat(table.cells().get(1).rowRange()).isEqualTo(new TrustCellRange(0, 0));
        assertThat(table.cells().get(1).columnRange()).isEqualTo(new TrustCellRange(1, 1));
        assertThat(table.cells().get(2).rowRange()).isEqualTo(new TrustCellRange(1, 1));
        assertThat(table.cells().get(2).columnRange()).isEqualTo(new TrustCellRange(1, 1));
        assertThat(table.cells()).allSatisfy(cell -> assertThat(cell.boundingBox()).isPresent());
        assertThat(document.body().units())
                .filteredOn(unit -> unit.kind() == TrustUnitKind.TABLE_CELL)
                .hasSize(3);
    }

    @Test
    void multiPageBorderedTableContinuationDeduplicatesHeaderAndKeepsCellPages() throws Exception {
        var document = TrustDocumentParser.parse(writeMultiPageContinuedTablePdf());

        assertThat(document.body().tables()).hasSize(1);
        var table = document.body().tables().getFirst();
        assertThat(table.pageNumber()).isEqualTo(1);
        assertThat(table.cells()).extracting(TrustTableCell::text)
                .containsExactly("Name", "Score", "Alex", "98", "Bea", "97");
        assertThat(table.cells().get(0).rowRange()).isEqualTo(new TrustCellRange(0, 0));
        assertThat(table.cells().get(2).rowRange()).isEqualTo(new TrustCellRange(1, 1));
        assertThat(table.cells().get(4).rowRange()).isEqualTo(new TrustCellRange(2, 2));

        var tableCellUnits = document.body().units().stream()
                .filter(unit -> unit.kind() == TrustUnitKind.TABLE_CELL)
                .toList();
        assertThat(tableCellUnits).hasSize(6);
        assertThat(tableCellUnits).extracting(unit -> unit.content().text())
                .containsExactly("Name", "Score", "Alex", "98", "Bea", "97");
        assertThat(tableCellUnits.get(0).location().page()).isEqualTo(1);
        assertThat(tableCellUnits.get(4).location().page()).isEqualTo(2);
        assertThat(tableCellUnits.get(5).location().page()).isEqualTo(2);
    }

    @Test
    void borderedTableSkipsDegenerateOffPageCellRegions() throws Exception {
        var pdf = writeOffPageGridCellPdf();

        assertThatCode(() -> TrustDocumentParser.parse(pdf)).doesNotThrowAnyException();
    }

    @Test
    void benchmarkScoresMergedTableCellSpanRecovery() throws Exception {
        var pdf = writeMergedCellTablePdf();
        var benchmarkCase = ParserBenchmarkCase.fromPdf(
                "merged-table-real-pdf",
                pdf,
                "| Header |  |\n| --- | --- |\n| A | B |\n",
                expectedDocument());

        var result = ParserBenchmarkRunner.evaluate(List.of(benchmarkCase)).getFirst();

        assertThat(result.metric("table_cell_f1")).isEqualTo(1.0);
        ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("table_cell_f1", 1.0));
    }

    @Test
    void benchmarkScoresRowSpanTableCellRecovery() throws Exception {
        var pdf = writeRowSpanTablePdf();
        var benchmarkCase = ParserBenchmarkCase.fromPdf(
                "row-span-table-real-pdf",
                pdf,
                "| Role | Top |\n| --- | --- |\n| Role | Bottom |\n",
                expectedRowSpanDocument());

        var result = ParserBenchmarkRunner.evaluate(List.of(benchmarkCase)).getFirst();

        assertThat(result.metric("table_cell_f1")).isEqualTo(1.0);
        ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("table_cell_f1", 1.0));
    }

    @Test
    void benchmarkScoresMultiPageTableContinuationRecovery() throws Exception {
        var pdf = writeMultiPageContinuedTablePdf();
        var benchmarkCase = ParserBenchmarkCase.fromPdf(
                "multi-page-continued-table-real-pdf",
                pdf,
                "| Name | Score |\n| --- | --- |\n| Alex | 98 |\n| Bea | 97 |\n",
                expectedMultiPageContinuationDocument());

        var result = ParserBenchmarkRunner.evaluate(List.of(benchmarkCase)).getFirst();

        assertThat(result.metric("table_cell_f1")).isEqualTo(1.0);
        ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("table_cell_f1", 1.0));
    }

    private Path writeMergedCellTablePdf() throws IOException {
        var path = tempDir.resolve("merged-cell-table.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                drawLine(stream, 72, 720, 360, 720);
                drawLine(stream, 360, 720, 360, 640);
                drawLine(stream, 360, 640, 72, 640);
                drawLine(stream, 72, 640, 72, 720);
                drawLine(stream, 72, 680, 360, 680);
                drawLine(stream, 216, 680, 216, 640);
                writeText(stream, "Header", 155, 695);
                writeText(stream, "A", 120, 655);
                writeText(stream, "B", 265, 655);
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path writeRowSpanTablePdf() throws IOException {
        var path = tempDir.resolve("row-span-table.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                drawLine(stream, 72, 720, 360, 720);
                drawLine(stream, 360, 720, 360, 640);
                drawLine(stream, 360, 640, 72, 640);
                drawLine(stream, 72, 640, 72, 720);
                drawLine(stream, 216, 720, 216, 640);
                drawLine(stream, 216, 680, 360, 680);
                writeText(stream, "Role", 120, 675);
                writeText(stream, "Top", 265, 695);
                writeText(stream, "Bottom", 255, 655);
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path writeMultiPageContinuedTablePdf() throws IOException {
        var path = tempDir.resolve("continued-table.pdf");
        try (var pdf = new PDDocument()) {
            var first = new PDPage();
            var second = new PDPage();
            pdf.addPage(first);
            pdf.addPage(second);
            try (var stream = new PDPageContentStream(pdf, first)) {
                drawTwoColumnTable(stream, "Alex", "98");
            }
            try (var stream = new PDPageContentStream(pdf, second)) {
                drawTwoColumnTable(stream, "Bea", "97");
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path writeOffPageGridCellPdf() throws IOException {
        var path = tempDir.resolve("off-page-grid-cell.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                drawLine(stream, -20, 720, 120, 720);
                drawLine(stream, -20, 680, 120, 680);
                drawLine(stream, -20, 720, -20, 680);
                drawLine(stream, -10, 720, -10, 680);
                drawLine(stream, 120, 720, 120, 680);
                writeText(stream, "Visible", 20, 695);
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private static void drawTwoColumnTable(PDPageContentStream stream, String name, String score) throws IOException {
        drawLine(stream, 72, 720, 360, 720);
        drawLine(stream, 72, 680, 360, 680);
        drawLine(stream, 72, 640, 360, 640);
        drawLine(stream, 72, 720, 72, 640);
        drawLine(stream, 216, 720, 216, 640);
        drawLine(stream, 360, 720, 360, 640);
        writeText(stream, "Name", 90, 695);
        writeText(stream, "Score", 240, 695);
        writeText(stream, name, 90, 655);
        writeText(stream, score, 240, 655);
    }

    private static void drawLine(PDPageContentStream stream, float x0, float y0, float x1, float y1)
            throws IOException {
        stream.moveTo(x0, y0);
        stream.lineTo(x1, y1);
        stream.stroke();
    }

    private static void writeText(PDPageContentStream stream, String text, float x, float y) throws IOException {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }

    private static TrustDocument parsePdfBox(Path pdf) throws ParseException {
        var request = new ParserRequest(
                pdf,
                TrustDocumentParser.sha256SourceFile(pdf),
                ParserPreset.LITE.parserRun("pdfbox"),
                true,
                false);
        return new PdfBoxParserBackend().parse(request).withEvaluatedAuditGrade();
    }

    private static TrustDocument expectedDocument() {
        var table = new TrustTable(
                "table-0001",
                1,
                Optional.empty(),
                new Confidence(1.0, "expected fixture"),
                List.of(
                        expectedCell(0, 0, 0, 1, "Header"),
                        expectedCell(1, 1, 0, 0, "A"),
                        expectedCell(1, 1, 1, 1, "B")));
        return new TrustDocument(
                        "expected-merged-table",
                        new TrustDocumentSource(
                                "expected.pdf",
                                "sha256:expected",
                                new DocumentMetadata("expected.pdf", 1, Optional.empty())),
                        new TrustDocumentBody(
                                List.of(new TrustPage(1, 1000, 1000, true, "sha256:page")),
                                List.of(),
                                List.of(table)),
                        new ParserRun("1.0.0", "table-lite", "fixture", List.of(), List.of()),
                        AuditGradeStatus.UNKNOWN)
                .withEvaluatedAuditGrade();
    }

    private static TrustDocument expectedRowSpanDocument() {
        var table = new TrustTable(
                "table-0001",
                1,
                Optional.empty(),
                new Confidence(1.0, "expected fixture"),
                List.of(
                        expectedCell(0, 1, 0, 0, "Role"),
                        expectedCell(0, 0, 1, 1, "Top"),
                        expectedCell(1, 1, 1, 1, "Bottom")));
        return new TrustDocument(
                        "expected-row-span-table",
                        new TrustDocumentSource(
                                "expected.pdf",
                                "sha256:expected",
                                new DocumentMetadata("expected.pdf", 1, Optional.empty())),
                        new TrustDocumentBody(
                                List.of(new TrustPage(1, 1000, 1000, true, "sha256:page")),
                                List.of(),
                                List.of(table)),
                        new ParserRun("1.0.0", "table-lite", "fixture", List.of(), List.of()),
                        AuditGradeStatus.UNKNOWN)
                .withEvaluatedAuditGrade();
    }

    private static TrustDocument expectedMultiPageContinuationDocument() {
        var table = new TrustTable(
                "table-0001",
                1,
                Optional.empty(),
                new Confidence(1.0, "expected fixture"),
                List.of(
                        expectedCell(0, 0, 0, 0, "Name"),
                        expectedCell(0, 0, 1, 1, "Score"),
                        expectedCell(1, 1, 0, 0, "Alex"),
                        expectedCell(1, 1, 1, 1, "98"),
                        expectedCell(2, 2, 0, 0, "Bea"),
                        expectedCell(2, 2, 1, 1, "97")));
        return new TrustDocument(
                        "expected-multi-page-table",
                        new TrustDocumentSource(
                                "expected.pdf",
                                "sha256:expected",
                                new DocumentMetadata("expected.pdf", 2, Optional.empty())),
                        new TrustDocumentBody(
                                List.of(
                                        new TrustPage(1, 1000, 1000, true, "sha256:page-1"),
                                        new TrustPage(2, 1000, 1000, true, "sha256:page-2")),
                                List.of(),
                                List.of(table)),
                        new ParserRun("1.0.0", "table-lite", "fixture", List.of(), List.of()),
                        AuditGradeStatus.UNKNOWN)
                .withEvaluatedAuditGrade();
    }

    private static TrustTableCell expectedCell(int rowStart, int rowEnd, int columnStart, int columnEnd, String text) {
        return new TrustTableCell(
                "cell-0001-%04d-%04d".formatted(rowStart, columnStart),
                new TrustCellRange(rowStart, rowEnd),
                new TrustCellRange(columnStart, columnEnd),
                Optional.empty(),
                text);
    }
}
