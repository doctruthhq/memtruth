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
        assertThat(document.toMarkdownClean()).contains("| Environment, Health and Safety | 834 | 15.9% | 345 | 1.7% |");
        assertThat(document.toMarkdownClean()).contains("| Total Applicable Compliances | 669 |");
        assertThat(document.toMarkdownClean()).contains("| Compliances with imprisonment | 461 |");
        assertThat(document.toMarkdownClean()).contains("| Percentage of imprisonment clauses | 69% |");
        assertThat(document.toMarkdownClean()).contains("""
                |  | Small | Medium | Large |
                | --- | --- | --- | --- |
                | Total Applicable Compliances | 669 | 3,109 | 5,796 |""");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderLongTextComparativeTableDoesNotCollapseToSingleRow() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000088"));
        var markdown = document.toMarkdownClean();

        assertThat(markdown).contains("| Jurisdiction | GATS XVII Reservation (1994) | Foreign Ownership Permitted |");
        assertThat(markdown).contains("| Argentina | Y | Y | Prohibition on ownership of property");
        assertThat(markdown).contains("| Australia | N | Y | Approval is needed from the Treasurer");
        assertThat(markdown).doesNotContain("| Restrictions on Land Ownership by Foreigners in Selected Jurisdictions Comparative Summary Table Jurisdiction");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderDenseMatrixTableSplitsSpanningHeaderCells() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000189"));
        var markdown = document.toMarkdownClean();

        assertThat(markdown).contains("| Model | Alpaca-GPT4 | OpenOrca | Synth. Math-Instruct | H6 (Avg.) | ARC |");
        assertThat(markdown).contains("| SFT v1 | O | ✗ | ✗ | 69.15 | 67.66 | 86.03 |");
        assertThat(markdown).doesNotContain("| Model | Alpaca-GPT4 OpenOrca Synth. Math-Instruct H6 (Avg.)");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderCopyrightPosterDoesNotPromoteFooterFurnitureAsTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000141"));
        var markdown = document.toMarkdownClean();

        assertThat(document.body().tables()).isEmpty();
        assertThat(markdown).doesNotContain("| and .org | and .org | and .org |");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderContentsPageDoesNotPromoteRepeatedPageTextAsTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000198"));
        var markdown = document.toMarkdownClean();

        assertThat(markdown).contains("Contents");
        assertThat(markdown).contains("Overview of OCR Pack");
        assertThat(markdown).doesNotContain("| Contents 1. Overview of OCR Pack");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderTableOfContentsDoesNotPromoteToTwoColumnTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000044"));
        var markdown = document.toMarkdownClean();

        assertThat(document.body().tables()).isEmpty();
        assertThat(markdown).contains("Table of Contents");
        assertThat(markdown).contains("Executive Summary");
        assertThat(markdown).doesNotContain("| Table of Contents Executive Summary | 4 |");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderTwoColumnNarrativeDoesNotPromoteToTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000196"));
        var markdown = document.toMarkdownClean();

        assertThat(markdown).contains("# B.3 Prompt Engineering");
        assertThat(markdown).contains("# B.4 Instruction Tuning");
        assertThat(markdown).doesNotContain("| plexity when compared");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderColumnStreamGovernmentPositionsTableBecomesStructuredTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000051"));
        var markdown = document.toMarkdownClean();

        assertThat(document.body().tables()).isNotEmpty();
        assertThat(markdown).contains("| Government Position | No. of Seats |");
        assertThat(markdown).contains("| Senate | 24 | 8.3 | 16.7 |");
        assertThat(markdown).contains("| City/Municipal Vice Mayor | 1,578 | 6.5 | 14.9 |");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderColumnStreamObserverTableBecomesStructuredTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000045"));
        var markdown = document.toMarkdownClean();

        assertThat(document.body().tables()).isNotEmpty();
        assertThat(markdown).contains("| No. | Name of organization | Number of accredited observers |");
        assertThat(markdown).contains("| 1 | Union of Youth Federations of Cambodia (UYFC) | 17,266 |");
        assertThat(markdown).contains("| 7 | Traditional and Modern Mental Health Organization | 15 |");
        assertThat(markdown).contains("|  | Total | 27,926 |");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderDataOnlyContinuationTableBecomesStructuredTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000053"));
        var markdown = document.toMarkdownClean();

        assertThat(document.body().tables()).isNotEmpty();
        assertThat(markdown).contains("| IX - Zamboanga Peninsula | 4 | 2 | 4 |");
        assertThat(markdown).contains("| XII - SOCCSKSARGEN | 2 | 2 | 1 |");
        assertThat(markdown).contains("| TOTAL (w/o Party- List) | 45 | 51 | 68 |");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderTextContinuationPromotionalMaterialsTableBecomesStructuredTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000178"));
        var markdown = document.toMarkdownClean();

        assertThat(document.body().tables()).isNotEmpty();
        assertThat(markdown).contains("| Communication Channel | Medium | Examples |");
        assertThat(markdown).contains("| Direct communications | Physical or digital | meetings, consultations, listening sessions, email lists |");
        assertThat(markdown).contains("| Goodies | Primarily physical | pens, notepads, bookmarks, stickers, buttons, etc |");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderLongTextServiceFlowTableBecomesStructuredTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000200"));
        var markdown = document.toMarkdownClean();

        assertThat(document.body().tables()).isNotEmpty();
        assertThat(markdown).contains("| Service Stage | Function Name | Explanation | Expected Benefit |");
        assertThat(markdown).contains("| 1. Project creation | Project creation and management | Select document type to automatically run project creation, Pipeline configuration with recommended Modelset and Endpoint deployment |");
        assertThat(markdown).contains("|  | Create and manage Labeling | Creating a Labeling Space to manage raw data annotation, managing labeling resources |");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderMeasurementMatrixTableBecomesStructuredTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000117"));
        var markdown = document.toMarkdownClean();

        assertThat(document.body().tables()).isNotEmpty();
        assertThat(markdown).contains("| Saccharometer | DI Water | Glucose Solution | Yeast Suspension |");
        assertThat(markdown).contains("| 2 | 24 ml | 0 ml | 4 ml |");
        assertThat(markdown).contains("| 4 | 4 ml | 12 ml | 12 ml |");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderTwoColumnSuppliesTableBecomesStructuredTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000121"));
        var markdown = document.toMarkdownClean();

        assertThat(document.body().tables()).isNotEmpty();
        assertThat(markdown).contains("| Reagents | Supplies and Equipment |");
        assertThat(markdown).contains("| At each student station: Resuspended DNA or ethanol precipitates from Part 1*");
        assertThat(markdown).contains("Microcentrifuge tube rack 3 1.5-mL microcentrifuge tubes Micropipet, 1- 20 μL Micropipet tips");
        assertThat(markdown).contains("Sterile distilled or deionized water |");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderAiPackComparisonTableBecomesStructuredTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000182"));
        var markdown = document.toMarkdownClean();

        assertThat(document.body().tables()).isNotEmpty();
        assertThat(markdown).contains("|  | OCR | Recommendation | Product semantic search |");
        assertThat(markdown).contains("| Pack | A solution that recognizes characters in an image and extracts necessary information |");
        assertThat(markdown).contains("| Application | Applicable to all fields that require text extraction from standardized documents");
        assertThat(markdown).contains("| Highlight | Achieved 1 place in the OCR World Competition");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderSpeciesListBecomesStructuredTwoColumnTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000132"));
        var markdown = document.toMarkdownClean();

        assertThat(document.body().tables()).isNotEmpty();
        assertThat(markdown).contains("| Potosi Pupfish | Fish species on IUCN Red List Cyprinodon alvarezi |");
        assertThat(markdown).contains("| La Palma Pupfish | Cyprinodon longidorsalis |");
        assertThat(markdown).contains("| Golden Skiffia | Skiffia francesae |");
    }

    @Test
    @EnabledIf("hasOpenDataLoaderBench")
    void opendataloaderExcelProjectionTableStaysOneStructuredTable() throws Exception {
        var document = parsePdfBox(opendataloaderBenchPdf("01030000000128"));
        var markdown = document.toMarkdownClean();

        assertThat(document.body().tables()).isNotEmpty();
        assertThat(markdown).contains("|  | A | B | C | D | E |");
        assertThat(markdown).contains("| 1 | time | observed | Forecast(observed) | Lower Confidence Bound(observed) | Upper Confidence Bound(observed) |");
        assertThat(markdown).contains("| 15 | 13 |  | 24.75424515 | 22.75 | 26.75 |");
        assertThat(markdown).doesNotContain("| 1 | A time observed | B Forecast(observed) |");
        assertThat(markdown).doesNotContain("\n| A | B | C | D | E |\n");
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
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000083"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000088"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000189"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000141"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000198"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000051"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000045"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000053"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000178"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000200"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000117"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000121"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000182"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000132"))
                && Files.isRegularFile(opendataloaderBenchPdf("01030000000128"));
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
