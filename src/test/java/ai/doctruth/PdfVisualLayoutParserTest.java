package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfVisualLayoutParserTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("PDF layout utility classes reject reflective instantiation")
    void utilityConstructorsRejectReflectionInstantiation() {
        assertThatThrownBy(() -> assertUtilityConstructorRejects(PdfPageGraphicsExtractor.class))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertUtilityConstructorRejects(PdfTextPositionMetrics.class))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("same-row text in separate visual columns is not merged into one layout block")
    void twoColumnRowsRemainSeparateBlocks() throws Exception {
        var pdfPath = writePositionedPdf(
                tempDir,
                List.of(
                        new PositionedRun("Contact", 50f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("WORK EXPERIENCE", 320f, 720f, 14f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("+601127640924", 50f, 700f, 12f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("Process Assistant Engineer", 320f, 700f, 12f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("Address", 50f, 680f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("SPI and FPY Management", 320f, 680f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD)));

        var doc = PdfDocumentParser.parse(pdfPath);
        var texts = doc.sections().stream().map(section -> ((TextSection) section).text()).toList();

        assertThat(texts).anySatisfy(text -> assertThat(text).contains("Contact").doesNotContain("WORK EXPERIENCE"));
        assertThat(texts).anySatisfy(text -> assertThat(text).contains("WORK EXPERIENCE").doesNotContain("Contact"));
        assertThat(texts).noneSatisfy(text -> assertThat(text).contains("Contact").contains("WORK EXPERIENCE"));
    }

    @Test
    @DisplayName("sparse right-aligned dates remain attached to the same education entry block")
    void rightAlignedEducationDatesRemainWithEntry() throws Exception {
        var pdfPath = writePositionedPdf(
                tempDir,
                List.of(
                        new PositionedRun("EDUCATION", 50f, 720f, 14f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Foundation in Management", 50f, 700f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("2018 - 2019", 455f, 700f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("UNITAR International University", 50f, 684f, 12f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun(
                                "- Relevant coursework in Principles of Management, Communication Skills",
                                65f,
                                668f,
                                12f,
                                Standard14Fonts.FontName.HELVETICA)));

        var doc = PdfDocumentParser.parse(pdfPath);
        var texts = doc.sections().stream().map(section -> ((TextSection) section).text()).toList();

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("Foundation in Management")
                .contains("2018 - 2019")
                .contains("UNITAR International University"));
        assertThat(texts).noneSatisfy(text -> assertThat(text)
                .contains("2018 - 2019")
                .doesNotContain("Foundation in Management"));
    }

    @Test
    @DisplayName("PDF horizontal separator lines split otherwise dense same-column blocks")
    void horizontalSeparatorLineSplitsDenseBlocks() throws Exception {
        var pdfPath = writePositionedPdfWithHorizontalRules(
                tempDir,
                List.of(
                        new PositionedRun("Process assistant summary line one", 50f, 720f, 12f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("Process assistant summary line two", 50f, 712f, 12f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("Education entry line one", 50f, 704f, 12f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("Education entry line two", 50f, 696f, 12f, Standard14Fonts.FontName.HELVETICA)),
                List.of(new HorizontalRule(45f, 708f, 540f, 708f)));

        var doc = PdfDocumentParser.parse(pdfPath);
        var texts = doc.sections().stream().map(section -> ((TextSection) section).text()).toList();

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("Process assistant summary line one")
                .contains("Process assistant summary line two")
                .doesNotContain("Education entry"));
        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("Education entry line one")
                .contains("Education entry line two")
                .doesNotContain("Process assistant"));
    }

    @Test
    @DisplayName("bold responsibility headings inside a dense work experience split oversized blocks")
    void denseWorkExperienceSplitsAtBoldResponsibilityHeadings() throws Exception {
        var pdfPath = writePositionedPdf(
                tempDir,
                List.of(
                        new PositionedRun("WORK EXPERIENCE", 50f, 720f, 14f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun(
                                "Aug 2024 - Present Process Assistant Engineer at Kaifa Technology",
                                50f,
                                700f,
                                10f,
                                Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun(
                                "SPI (Solder Paste Inspection) & FPY Management:",
                                50f,
                                680f,
                                11f,
                                Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun(
                                "- Developed and managed SPI programs to optimize solder paste application.",
                                65f,
                                664f,
                                10f,
                                Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("- Analyzed and improved FPY rates to reduce defects.", 65f, 650f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("Stencil & Printer Parameter Optimization:", 50f, 632f, 11f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("- Modified stencil openings and printer parameters.", 65f, 616f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun(
                                "- Implemented best practices to improve process variation.",
                                65f,
                                602f,
                                10f,
                                Standard14Fonts.FontName.HELVETICA)));

        var doc = PdfDocumentParser.parse(pdfPath);
        var texts = doc.sections().stream().map(section -> ((TextSection) section).text()).toList();

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("SPI (Solder Paste Inspection)")
                .contains("Developed and managed SPI")
                .doesNotContain("Stencil & Printer"));
        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("Stencil & Printer")
                .contains("Modified stencil openings")
                .doesNotContain("SPI (Solder Paste Inspection)"));
    }

    @Test
    @DisplayName("dense resume section headings split work experience from education")
    void denseSectionHeadingsSplitWorkExperienceFromEducation() throws Exception {
        var pdfPath = writePositionedPdf(
                tempDir,
                List.of(
                        new PositionedRun("WORK EXPERIENCE", 50f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Process Assistant Engineer at Kaifa Technology", 50f, 708f, 10f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("- Developed SPI programs for manufacturing quality.", 65f, 696f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("- Reduced process defects with FPY analysis.", 65f, 684f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("EDUCATION", 50f, 672f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("B.Sc in Applied Science (Electronic and Instrumentation)", 50f, 660f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("University Malaysia Terengganu", 50f, 648f, 10f, Standard14Fonts.FontName.HELVETICA)));

        var doc = PdfDocumentParser.parse(pdfPath);
        var texts = doc.sections().stream().map(section -> ((TextSection) section).text()).toList();

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("WORK EXPERIENCE")
                .contains("Process Assistant Engineer")
                .doesNotContain("EDUCATION")
                .doesNotContain("B.Sc in Applied Science"));
        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("EDUCATION")
                .contains("B.Sc in Applied Science")
                .doesNotContain("Process Assistant Engineer"));
    }

    @Test
    @DisplayName("short sidebar label value rows stay in one language section block")
    void sidebarLanguageLabelValueRowsStayTogether() throws Exception {
        var pdfPath = writePositionedPdf(
                tempDir,
                List.of(
                        new PositionedRun("LANGUAGE", 50f, 720f, 14f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Malay", 50f, 700f, 10f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Fluent", 205f, 700f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("English", 50f, 684f, 10f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Fluent", 205f, 684f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("SKILL & EDUCATION", 50f, 650f, 14f, Standard14Fonts.FontName.HELVETICA_BOLD)));

        var doc = PdfDocumentParser.parse(pdfPath);
        var texts = doc.sections().stream().map(section -> ((TextSection) section).text()).toList();

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("LANGUAGE")
                .contains("Malay Fluent")
                .contains("English Fluent")
                .doesNotContain("SKILL & EDUCATION"));
    }

    @Test
    @DisplayName("dense numbered responsibility items split into separate evidence blocks")
    void denseNumberedResponsibilityItemsSplitIntoSeparateBlocks() throws Exception {
        var pdfPath = writePositionedPdf(
                tempDir,
                List.of(
                        new PositionedRun("PROJECT EXPERIENCE", 50f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("1. Planned supplier qualification and tender preparation.", 50f, 708f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("Continued contract approval and award follow-up.", 65f, 696f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("2. Reviewed supplier quotations and negotiated delivery terms.", 50f, 684f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("Continued procurement reporting for project stakeholders.", 65f, 672f, 10f, Standard14Fonts.FontName.HELVETICA)));

        var doc = PdfDocumentParser.parse(pdfPath);
        var texts = doc.sections().stream().map(section -> ((TextSection) section).text()).toList();

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("1. Planned supplier qualification")
                .contains("Continued contract approval")
                .doesNotContain("2. Reviewed supplier quotations"));
        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("2. Reviewed supplier quotations")
                .contains("Continued procurement reporting")
                .doesNotContain("1. Planned supplier qualification"));
    }

    @Test
    @DisplayName("non-bold responsibility headings ending with colon split dense work experience blocks")
    void nonBoldResponsibilityHeadingsSplitDenseWorkExperienceBlocks() throws Exception {
        var pdfPath = writePositionedPdf(
                tempDir,
                List.of(
                        new PositionedRun("WORK EXPERIENCE", 50f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Process Assistant Engineer at Kaifa Technology", 50f, 708f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("SPI (Solder Paste Inspection) & FPY Management:", 50f, 696f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("- Developed and managed SPI programs.", 65f, 684f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("- Improved FPY rates to reduce defects.", 65f, 672f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("Stencil & Printer Parameter Optimization:", 50f, 660f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("- Modified printer parameters.", 65f, 648f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("- Reduced process variation.", 65f, 636f, 10f, Standard14Fonts.FontName.HELVETICA)));

        var doc = PdfDocumentParser.parse(pdfPath);
        var texts = doc.sections().stream().map(section -> ((TextSection) section).text()).toList();

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("SPI (Solder Paste Inspection)")
                .contains("Developed and managed SPI")
                .doesNotContain("Stencil & Printer"));
        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("Stencil & Printer")
                .contains("Modified printer parameters")
                .doesNotContain("SPI (Solder Paste Inspection)"));
    }

    @Test
    @DisplayName("wide sidebar language proficiency rows stay in one language block")
    void wideSidebarLanguageProficiencyRowsStayTogether() throws Exception {
        var pdfPath = writePositionedPdf(
                tempDir,
                List.of(
                        new PositionedRun("LANGUAGE", 50f, 720f, 14f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Malay", 50f, 700f, 10f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Fluent", 285f, 700f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("English", 50f, 684f, 10f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Fluent", 285f, 684f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("SKILL & EDUCATION", 50f, 650f, 14f, Standard14Fonts.FontName.HELVETICA_BOLD)));

        var doc = PdfDocumentParser.parse(pdfPath);
        var texts = doc.sections().stream().map(section -> ((TextSection) section).text()).toList();

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("LANGUAGE")
                .contains("Malay Fluent")
                .contains("English Fluent")
                .doesNotContain("SKILL & EDUCATION"));
        assertThat(texts).noneSatisfy(text -> assertThat(text).isEqualTo("Fluent"));
    }

    @Test
    @DisplayName("sidebar contact labels do not attach to nearby main-column work text")
    void sidebarContactLabelsDoNotAttachToNearbyMainColumnText() throws Exception {
        var pdfPath = writePositionedPdf(
                tempDir,
                List.of(
                        new PositionedRun("WORK EXPERIENCE", 205f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Email", 50f, 700f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Internal Audits & Quality Management System:", 205f, 704f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("candidate@example.com", 50f, 684f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("Conduct internal audits to verify implementation.", 205f, 688f, 10f, Standard14Fonts.FontName.HELVETICA)));

        var doc = PdfDocumentParser.parse(pdfPath);
        var texts = doc.sections().stream().map(section -> ((TextSection) section).text()).toList();

        assertThat(texts).noneSatisfy(text -> assertThat(text).contains("Email").contains("Internal Audits"));
        assertThat(texts).noneSatisfy(text -> assertThat(text).contains("candidate@example.com").contains("Conduct internal audits"));
    }

    @Test
    @DisplayName("sidebar phone values do not merge with same-row main column responsibilities")
    void sidebarPhoneValuesDoNotAttachToSameRowMainColumnText() throws Exception {
        var pdfPath = writePositionedPdf(
                tempDir,
                List.of(
                        new PositionedRun("WORK EXPERIENCE", 205f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Phone", 50f, 704f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("+601127640924", 50f, 684f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun(
                                "Establish and document procedures and specifications.",
                                205f,
                                684f,
                                10f,
                                Standard14Fonts.FontName.HELVETICA)));

        var doc = PdfDocumentParser.parse(pdfPath);
        var texts = doc.sections().stream().map(section -> ((TextSection) section).text()).toList();

        assertThat(texts).noneSatisfy(text -> assertThat(text)
                .contains("+601127640924")
                .contains("Establish and document"));
    }

    @Test
    @DisplayName("overlapping duplicate PDF text is suppressed before block grouping")
    void overlappingDuplicateTextIsSuppressedBeforeBlockGrouping() throws Exception {
        var pdfPath = writePositionedPdf(
                tempDir,
                List.of(
                        new PositionedRun("WORK EXPERIENCE", 50f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                        new PositionedRun("Quality Engineer", 50f, 700f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("Quality Engineer", 50f, 700f, 10f, Standard14Fonts.FontName.HELVETICA),
                        new PositionedRun("- Managed inspection reports.", 65f, 684f, 10f, Standard14Fonts.FontName.HELVETICA)));

        var doc = PdfDocumentParser.parse(pdfPath);
        var text = doc.sections().stream()
                .map(section -> ((TextSection) section).text())
                .reduce("", (left, right) -> left + "\n" + right);

        assertThat(text).containsOnlyOnce("Quality Engineer");
    }

    private static Path writePositionedPdf(Path dir, List<PositionedRun> runs) throws IOException {
        return writePositionedPdfWithHorizontalRules(dir, runs, List.of());
    }

    private static void assertUtilityConstructorRejects(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }

    private static Path writePositionedPdfWithHorizontalRules(
            Path dir, List<PositionedRun> runs, List<HorizontalRule> rules) throws IOException {
        var path = dir.resolve("doc-" + System.nanoTime() + ".pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
                for (var rule : rules) {
                    cs.moveTo(rule.x1(), rule.y1());
                    cs.lineTo(rule.x2(), rule.y2());
                    cs.stroke();
                }
                for (var run : runs) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(run.fontName()), run.fontSize());
                    cs.newLineAtOffset(run.x(), run.y());
                    cs.showText(run.text());
                    cs.endText();
                }
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    record PositionedRun(String text, float x, float y, float fontSize, Standard14Fonts.FontName fontName) {}

    record HorizontalRule(float x1, float y1, float x2, float y2) {}
}
