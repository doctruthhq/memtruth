package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

class PdfBorderlessTableExtractionTest {

    @TempDir
    Path tempDir;

    @Test
    void alignedTextColumnsProduceStructuredBorderlessTable() throws Exception {
        var document = parsePdfBox(writeBorderlessTablePdf());

        assertThat(document.body().tables()).hasSize(1);
        var table = document.body().tables().getFirst();
        assertThat(table.cells()).extracting(TrustTableCell::text)
                .containsExactly("Name", "Score", "Alex", "98");
        assertThat(table.cells()).allSatisfy(cell -> assertThat(cell.boundingBox()).isPresent());
        assertThat(document.body().units())
                .filteredOn(unit -> unit.kind() == TrustUnitKind.TABLE_CELL)
                .hasSize(4);
    }

    @Test
    void raggedAlignedTextRowsReconstructBlankTableCells() throws Exception {
        var document = parsePdfBox(writeRaggedBorderlessTablePdf());

        assertThat(document.body().tables()).hasSize(1);
        var table = document.body().tables().getFirst();
        assertThat(table.cells()).extracting(TrustTableCell::text)
                .containsExactly("Name", "Role", "Score", "Alex", "", "98", "Blair", "Ops", "91");
        assertThat(document.toMarkdownClean()).contains("""
                | Name | Role | Score |
                | --- | --- | --- |
                | Alex |  | 98 |
                | Blair | Ops | 91 |""");
    }

    @Test
    void longHeaderNumericRowsProduceBorderlessTable() throws Exception {
        var document = parsePdfBox(writeLongHeaderNumericTablePdf());

        assertThat(document.body().tables()).hasSize(1);
        assertThat(document.toMarkdownClean()).contains("""
                | Temperature (degree C) | Kinematic viscosity coefficient v (m2/s) | Temperature (degree C) | Kinematic viscosity coefficient v (m2/s) |
                | --- | --- | --- | --- |
                | 0 | 1.793E-06 | 25 | 8.930E-07 |
                | 1 | 1.732E-06 | 26 | 8.760E-07 |""");
    }

    @Test
    void pdfBoxParserKeepsBorderlessTableInlineBetweenTextBlocks() throws Exception {
        var document = parsePdfBox(writeTextTableTextPdf());

        assertThat(document.toMarkdownClean()).isEqualTo("""
                Before table

                | Name | Score |
                | --- | --- |
                | Alex | 98 |

                After table
                """);
    }

    @Test
    void benchmarkScoresBorderlessTableCellRecovery() throws Exception {
        var pdf = writeBorderlessTablePdf();
        var benchmarkCase = ParserBenchmarkCase.fromPdf(
                "borderless-table-real-pdf", pdf, "| Name | Score |\n| --- | --- |\n| Alex | 98 |\n", expectedDocument());

        var result = ParserBenchmarkRunner.evaluate(List.of(benchmarkCase)).getFirst();

        assertThat(result.metric("table_cell_f1")).isEqualTo(1.0);
        ParserBenchmarkRunner.requireMinimums(List.of(result), Map.of("table_cell_f1", 1.0));
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderYearTablesBecomeStructuredTables() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000127"));

        assertThat(document.body().tables()).isNotEmpty();
        assertThat(document.toMarkdownClean()).contains("""
                | Year | 3-Year | 5-Year | 7-Year |
                | --- | --- | --- | --- |
                | 1 | 33.0% | 20.00% | 14.29% |""");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderComparativeTablesBecomeStructuredTables() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000083"));

        assertThat(document.body().tables()).isNotEmpty();
        assertThat(document.toMarkdownClean()).contains("""
                | Category | Number of clauses in Union laws | In percent | Number of clauses in State laws | In percent |
                | --- | --- | --- | --- | --- |
                | Commercial | 529 | 10.1% | 817 | 3.9% |""");
    }

    private Path writeBorderlessTablePdf() throws IOException {
        var path = tempDir.resolve("borderless-table.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                writeText(stream, "Name", 80, 700);
                writeText(stream, "Score", 220, 700);
                writeText(stream, "Alex", 80, 670);
                writeText(stream, "98", 220, 670);
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path writeLongHeaderNumericTablePdf() throws IOException {
        var path = tempDir.resolve("long-header-numeric-table.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(1000, 792));
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                writeText(stream, "Temperature (degree C)", 40, 700, 8);
                writeText(stream, "Kinematic viscosity coefficient v (m2/s)", 280, 700, 8);
                writeText(stream, "Temperature (degree C)", 560, 700, 8);
                writeText(stream, "Kinematic viscosity coefficient v (m2/s)", 760, 700, 8);
                writeText(stream, "0", 40, 670, 8);
                writeText(stream, "1.793E-06", 280, 670, 8);
                writeText(stream, "25", 560, 670, 8);
                writeText(stream, "8.930E-07", 760, 670, 8);
                writeText(stream, "1", 40, 640, 8);
                writeText(stream, "1.732E-06", 280, 640, 8);
                writeText(stream, "26", 560, 640, 8);
                writeText(stream, "8.760E-07", 760, 640, 8);
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path writeRaggedBorderlessTablePdf() throws IOException {
        var path = tempDir.resolve("ragged-borderless-table.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                writeText(stream, "Name", 80, 700);
                writeText(stream, "Role", 220, 700);
                writeText(stream, "Score", 340, 700);
                writeText(stream, "Alex", 80, 670);
                writeText(stream, "98", 340, 670);
                writeText(stream, "Blair", 80, 640);
                writeText(stream, "Ops", 220, 640);
                writeText(stream, "91", 340, 640);
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path writeTextTableTextPdf() throws IOException {
        var path = tempDir.resolve("text-table-text.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                writeText(stream, "Before table", 80, 735);
                writeText(stream, "Name", 80, 700);
                writeText(stream, "Score", 220, 700);
                writeText(stream, "Alex", 80, 670);
                writeText(stream, "98", 220, 670);
                writeText(stream, "After table", 80, 620);
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private static void writeText(PDPageContentStream stream, String text, float x, float y) throws IOException {
        writeText(stream, text, x, y, 12);
    }

    private static void writeText(PDPageContentStream stream, String text, float x, float y, float fontSize) throws IOException {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), fontSize);
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

    private static boolean hasOpenDataLoaderBench() {
        return Files.isRegularFile(opendataloaderBenchPdf("01030000000127"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000083"));
    }

    private static Path opendataloaderBenchPdf(String documentId) {
        return Path.of("third_party/opendataloader-bench/pdfs").resolve(documentId + ".pdf");
    }

    private static TrustDocument expectedDocument() {
        var table = new TrustTable(
                "table-0001",
                1,
                Optional.empty(),
                new Confidence(1.0, "expected fixture"),
                List.of(expectedCell(0, 0, "Name"), expectedCell(0, 1, "Score"),
                        expectedCell(1, 0, "Alex"), expectedCell(1, 1, "98")));
        return new TrustDocument(
                        "expected-borderless-table",
                        new TrustDocumentSource(
                                "expected.pdf",
                                "sha256:expected",
                                new DocumentMetadata("expected.pdf", 1, Optional.empty())),
                        new TrustDocumentBody(List.of(new TrustPage(1, 1000, 1000, true, "sha256:page")),
                                List.of(),
                                List.of(table)),
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
}
