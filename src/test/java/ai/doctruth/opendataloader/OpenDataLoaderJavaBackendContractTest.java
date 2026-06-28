package ai.doctruth.opendataloader;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import ai.doctruth.ParserPreset;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenDataLoaderJavaBackendContractTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesPdfIntoStructuredOpenDataLoaderResponse() throws Exception {
        var pdf = writePdf("OpenDataLoader Java Core", "Evidence backed parser response.");
        var backend = new OpenDataLoaderJavaBackend();

        var response = backend.parse(new OpenDataLoaderBackendRequest(pdf, ParserPreset.LITE));

        assertThat(response.backend()).isEqualTo("opendataloader-java-core");
        assertThat(response.schemaVersion()).isEqualTo("doctruth.opendataloader.backend.v1");
        assertThat(response.markdown()).contains("OpenDataLoader Java Core");
        assertThat(response.blocks()).isNotEmpty();
        assertThat(response.blocks().getFirst().id()).isNotBlank();
        assertThat(response.blocks().getFirst().kind()).isNotBlank();
        assertThat(response.blocks().getFirst().pageIndex()).isZero();
        assertThat(response.blocks().getFirst().readingOrder()).isGreaterThanOrEqualTo(0);
        assertThat(response.blocks().getFirst().text()).contains("OpenDataLoader Java Core");
        assertThat(response.blocks().getFirst().kind()).isEqualTo("heading");
        assertThat(response.blocks().getFirst().bbox()).isPresent();
        assertThat(response.tables()).isNotNull();
        assertThat(response.headings()).extracting(OpenDataLoaderBlock::text).contains("OpenDataLoader Java Core");
        assertThat(response.sourceMap()).isNotEmpty();
        assertThat(response.sourceMap().getFirst().unitId())
                .isEqualTo(response.blocks().getFirst().sourceUnitId());
        assertThat(response.warnings()).isNotNull();
        assertThat(response.metrics()).containsKey("elapsedMs");
        assertThat(response.trustDocument().parserRun().backend()).isEqualTo("opendataloader-java-core");
    }

    @Test
    void responseCanRoundTripThroughTrustDocumentWithoutLosingSourceRefs() throws Exception {
        var pdf = writePdf("TrustDocument source refs", "The source map must survive adaptation.");
        var response = new OpenDataLoaderJavaBackend().parse(new OpenDataLoaderBackendRequest(pdf, ParserPreset.LITE));

        assertThat(response.trustDocument().body().units()).isNotEmpty();
        assertThat(response.trustDocument().body().units().getFirst().evidence().evidenceSpanIds())
                .isNotEmpty();
        assertThat(response.sourceMap()).allSatisfy(ref -> {
            assertThat(ref.unitId()).isNotBlank();
            assertThat(ref.pageIndex()).isGreaterThanOrEqualTo(0);
            assertThat(ref.text()).isNotBlank();
        });
    }

    @Test
    void adjacentTableCaptionProjectsAsCaptionBlock() throws Exception {
        var response = new OpenDataLoaderJavaBackend()
                .parse(new OpenDataLoaderBackendRequest(writeCaptionedTablePdf(), ParserPreset.LITE));

        assertThat(response.blocks())
                .filteredOn(block -> "caption".equals(block.kind()))
                .extracting(OpenDataLoaderBlock::text)
                .contains("Table 1. Quarterly revenue by region");
    }

    @Test
    void repeatedTopBandRunningHeaderDoesNotProjectAsHeading() throws Exception {
        var response = new OpenDataLoaderJavaBackend()
                .parse(new OpenDataLoaderBackendRequest(writeRunningHeaderPdf(), ParserPreset.LITE));

        assertThat(response.markdown()).doesNotContain("# Probability, Combinatorics and Control");
        assertThat(response.headings())
                .extracting(OpenDataLoaderBlock::text)
                .containsExactly("Opening Context", "Main Result", "Proof Sketch")
                .doesNotContain("Probability, Combinatorics and Control");
    }

    @Test
    void bareNumberedChapterHeadingsProjectAsHeadingBlocks() throws Exception {
        var backend = new OpenDataLoaderJavaBackend();

        assertOpenDataLoaderHeading(
                backend, "01030000000002", "8 Choosing between Observer Models and Rejecting Participants");
        assertOpenDataLoaderHeading(backend, "01030000000004", "12 Conclusion");
    }

    @Test
    void dottedNumberedSectionHeadingsProjectAsHeadingBlocks() throws Exception {
        var backend = new OpenDataLoaderJavaBackend();

        assertOpenDataLoaderHeading(backend, "01030000000054", "2.1. Diesel and biodiesel use");
        assertOpenDataLoaderHeading(backend, "01030000000065", "5. Natural dispersal");
    }

    @Test
    void numberedHeadingContinuationLinesStayInsideHeadingBlocks() throws Exception {
        var backend = new OpenDataLoaderJavaBackend();

        assertOpenDataLoaderHeading(backend, "01030000000029", "6. Modeling the dynamics");
        assertOpenDataLoaderHeading(
                backend, "01030000000031", "8. Numerical computations in the combinatorial multiverse");
    }

    @Test
    void multiLineDocumentTitleFragmentsMergeIntoOneHeading() throws Exception {
        var response = new OpenDataLoaderJavaBackend()
                .parse(new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000085"), ParserPreset.LITE));

        assertThat(response.headings())
                .extracting(OpenDataLoaderBlock::text)
                .contains("Restrictions on Land Ownership by Foreigners in Selected Jurisdictions")
                .doesNotContain("Restrictions on Land Ownership", "by Foreigners in Selected", "Jurisdictions");
        assertThat(response.markdown())
                .contains("# Restrictions on Land Ownership by Foreigners in Selected Jurisdictions")
                .doesNotContain("# by Foreigners in Selected")
                .doesNotContain("# The Law Library of Congress, Global Legal Research Directorate");
    }

    @Test
    void romanNumeralHeadingFragmentsMergeAndSuppressRunningTitle() throws Exception {
        var response = new OpenDataLoaderJavaBackend()
                .parse(new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000080"), ParserPreset.LITE));

        assertThat(response.headings())
                .extracting(OpenDataLoaderBlock::text)
                .contains("III. Regulatory cholesterol")
                .doesNotContain("Jailed for Doing Business", "III.", "Regulatory", "cholesterol", "16");
        assertThat(response.markdown())
                .contains("# III. Regulatory cholesterol")
                .doesNotContain("# Jailed for Doing Business")
                .doesNotContain("# 16");
    }

    @Test
    void runningHeadersFiguresAndPageNumbersDoNotProjectAsHeadings() throws Exception {
        var backend = new OpenDataLoaderJavaBackend();

        var textileResponse = backend.parse(
                new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000013"), ParserPreset.LITE));
        assertThat(textileResponse.headings())
                .extracting(OpenDataLoaderBlock::text)
                .contains("4 Al-Sadu Symbols and Social Significance")
                .doesNotContain("Al-Ogayyel and Oskay");

        var migrationResponse = backend.parse(
                new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000077"), ParserPreset.LITE));
        assertThat(migrationResponse.headings())
                .extracting(OpenDataLoaderBlock::text)
                .contains("1.5. Migrant Workers More at Risk of COVID-19 Infection")
                .doesNotContain(
                        "9 Figure 1.9b. Deployment of Overseas Foreign Workers by sex, new hires only (in thousands)",
                        "ASEAN Mi gr at i on Out l ook");

        var wasteResponse = backend.parse(
                new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000067"), ParserPreset.LITE));
        assertThat(wasteResponse.headings())
                .extracting(OpenDataLoaderBlock::text)
                .contains("6.2 Waste Management")
                .doesNotContain(
                        "No Allocation", "Figure 20. Percentage of LGU Budget Allocated for Waste Management", "49");
    }

    @Test
    void singleWordAndInlineColonHeadingsSplitFromBodyText() throws Exception {
        var backend = new OpenDataLoaderJavaBackend();

        var stopResponse = backend.parse(
                new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000157"), ParserPreset.LITE));
        assertThat(stopResponse.headings())
                .extracting(OpenDataLoaderBlock::text)
                .contains("Stop")
                .doesNotContain("SIFTing Information | 69");
        assertThat(stopResponse.markdown()).startsWith("# Stop");

        var referenceFrameworksResponse = backend.parse(
                new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000146"), ParserPreset.LITE));
        assertThat(referenceFrameworksResponse.headings())
                .extracting(OpenDataLoaderBlock::text)
                .contains("Reference frameworks:");
        assertThat(referenceFrameworksResponse.markdown()).contains("# Reference frameworks:");
    }

    @Test
    void procedureStepsDoNotProjectAsHeadingBlocks() throws Exception {
        var response = new OpenDataLoaderJavaBackend()
                .parse(new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000115"), ParserPreset.LITE));

        assertThat(response.headings())
                .extracting(OpenDataLoaderBlock::text)
                .contains("Changing objectives:", "Steps for Using the Microscope:")
                .doesNotContain(
                        "1. Place",
                        "2. Click",
                        "3. Look into",
                        "4. Use",
                        "5. Rotate",
                        "6. Refocus using",
                        "7. Move",
                        "8. Now use");
        assertThat(response.markdown()).doesNotContain("# 1. Place").doesNotContain("# 8. Now use");
    }

    @Test
    void labProcedureActionStepsDoNotProjectAsHeadingBlocks() throws Exception {
        var backend = new OpenDataLoaderJavaBackend();

        var yeastResponse = backend.parse(
                new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000117"), ParserPreset.LITE));
        assertThat(yeastResponse.headings())
                .extracting(OpenDataLoaderBlock::text)
                .doesNotContain(
                        "2. Record a Hypothesis for",
                        "3. Predict",
                        "4. Perform",
                        "4. Carefully pour",
                        "5. Carefully tilt",
                        "6. Begin",
                        "7. Position");

        var dnaResponse = backend.parse(
                new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000121"), ParserPreset.LITE));
        assertThat(dnaResponse.headings())
                .extracting(OpenDataLoaderBlock::text)
                .doesNotContain("18. Briefly spin", "19. Allow");
    }

    @Test
    void tableOfContentsEntriesDoNotProjectAsDocumentHeadings() throws Exception {
        var backend = new OpenDataLoaderJavaBackend();

        var textbookResponse = backend.parse(
                new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000155"), ParserPreset.LITE));
        assertThat(textbookResponse.headings())
                .extracting(OpenDataLoaderBlock::text)
                .containsExactly("Contents");
        assertThat(textbookResponse.markdown())
                .contains("# Contents")
                .doesNotContain("# 1. Front Matter")
                .doesNotContain("# Instructor Resources");

        var ocrPackResponse = backend.parse(
                new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000198"), ParserPreset.LITE));
        assertThat(ocrPackResponse.headings())
                .extracting(OpenDataLoaderBlock::text)
                .containsExactly("Contents");
        assertThat(ocrPackResponse.markdown())
                .contains("# Contents")
                .doesNotContain("# 1. Overview of OCR Pack")
                .doesNotContain("# 5. FAQ");
    }

    @Test
    void joinedActivityHeadingsAreSplitFromBodyText() throws Exception {
        var response = new OpenDataLoaderJavaBackend()
                .parse(new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf("01030000000168"), ParserPreset.LITE));

        assertThat(response.markdown()).contains("# Activity 1: Determining pH With Indicator Strips (Field Method)");
        assertThat(response.markdown()).contains("# Activity 2: Determining Soil pH with a pH Meter");
        assertThat(response.headings())
                .extracting(OpenDataLoaderBlock::text)
                .contains(
                        "Activity 1: Determining pH With Indicator Strips (Field Method)",
                        "Activity 2: Determining Soil pH with a pH Meter");
    }

    private static void assertOpenDataLoaderHeading(
            OpenDataLoaderJavaBackend backend, String documentId, String expectedHeading) throws Exception {
        var response =
                backend.parse(new OpenDataLoaderBackendRequest(openDataLoaderBenchPdf(documentId), ParserPreset.LITE));

        assertThat(response.markdown()).contains("# " + expectedHeading);
        assertThat(response.markdown()).doesNotContain("\n" + expectedHeading + "\n");
        assertThat(response.headings()).extracting(OpenDataLoaderBlock::text).contains(expectedHeading);
    }

    private static Path openDataLoaderBenchPdf(String documentId) {
        var path = Path.of("third_party/opendataloader-bench/pdfs").resolve(documentId + ".pdf");
        assertThat(Files.isRegularFile(path))
                .as("OpenDataLoader bench fixture exists: %s", path)
                .isTrue();
        return path;
    }

    private Path writePdf(String firstLine, String secondLine) throws Exception {
        var path = tempDir.resolve(firstLine.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase() + ".pdf");
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                content.newLineAtOffset(72, 720);
                content.showText(firstLine);
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(0, -28);
                content.showText(secondLine);
                content.endText();
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private Path writeCaptionedTablePdf() throws Exception {
        var path = tempDir.resolve("captioned-table.pdf");
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                writeText(content, "Table 1. Quarterly revenue by region", 72, 705);
                drawLine(content, 72, 680, 360, 680);
                drawLine(content, 72, 640, 360, 640);
                drawLine(content, 72, 600, 360, 600);
                drawLine(content, 72, 680, 72, 600);
                drawLine(content, 216, 680, 216, 600);
                drawLine(content, 360, 680, 360, 600);
                writeText(content, "Region", 100, 655);
                writeText(content, "Revenue", 245, 655);
                writeText(content, "North", 100, 615);
                writeText(content, "$10M", 245, 615);
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private Path writeRunningHeaderPdf() throws Exception {
        var path = tempDir.resolve("running-header.pdf");
        var headings = new String[] {"Opening Context", "Main Result", "Proof Sketch"};
        try (var doc = new PDDocument()) {
            for (int i = 0; i < headings.length; i++) {
                var page = new PDPage();
                doc.addPage(page);
                try (var content = new PDPageContentStream(doc, page)) {
                    writeText(content, "Probability, Combinatorics and Control", 72, 760, 14, true);
                    writeText(content, headings[i], 72, 690, 16, true);
                    writeText(content, "Unique body paragraph for page " + (i + 1) + ".", 72, 630, 12, false);
                }
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private static void writeText(PDPageContentStream stream, String text, float x, float y) throws Exception {
        writeText(stream, text, x, y, 12, false);
    }

    private static void writeText(PDPageContentStream stream, String text, float x, float y, int size, boolean bold)
            throws Exception {
        stream.beginText();
        stream.setFont(
                new PDType1Font(bold ? Standard14Fonts.FontName.HELVETICA_BOLD : Standard14Fonts.FontName.HELVETICA),
                size);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }

    private static void drawLine(PDPageContentStream stream, float x0, float y0, float x1, float y1) throws Exception {
        stream.moveTo(x0, y0);
        stream.lineTo(x1, y1);
        stream.stroke();
    }
}
