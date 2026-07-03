package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfTwoColumnSemanticSectionTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("wide resume title does not make sidebar contact rows share a main-column profile block")
    void wideResumeTitleDoesNotCollapseSidebarAndMainColumnRows() throws Exception {
        var pdfPath = writePositionedPdf(List.of(
                run("AMIRUL IZZAT BIN RAMDZAN", 50f, 760f, 18f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("CONTACT", 50f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("PROFILE", 320f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("+6011-19822183", 50f, 700f, 10f, Standard14Fonts.FontName.HELVETICA),
                run("Experienced business development executive with insurance clients.", 320f, 700f),
                run("izzatramdzan216@gmail.com", 50f, 684f),
                run("Builds key account relationships and market analysis.", 320f, 684f)));

        var texts = parsedTexts(pdfPath);

        assertThat(texts)
                .noneSatisfy(text ->
                        assertThat(text).contains("+6011-19822183").contains("Experienced business development"));
        assertThat(texts).noneSatisfy(text -> assertThat(text)
                .contains("izzatramdzan216@gmail.com")
                .contains("Builds key account relationships"));
    }

    @Test
    @DisplayName("wide header rows do not pollute later two-column section grouping")
    void wideHeaderRowsDoNotPolluteTwoColumnSectionGrouping() throws Exception {
        var pdfPath = writePositionedPdf(List.of(
                run("MOHD SYAFIQ IZUAN BIN MOHD AZMI", 90f, 760f, 16f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("E-4-29 APARTMENT KENANGA TAMAN BUNGA RAYA, 48300 BUKIT BERUNTUNG", 50f, 735f),
                run("BUTIRAN DIRI", 50f, 700f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("Nombor I/C: 900502-08-5555", 50f, 684f),
                run("Umur: 34 Tahun", 50f, 672f),
                run("PENGALAMAN PEKERJAAN", 320f, 700f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("9/2023 - sekarang: Logistic Supervisor", 320f, 684f),
                run("TLS Transport Sdn Bhd", 360f, 672f),
                run("PENDIDIKAN", 50f, 640f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("SIJIL PELAJARAN MALAYSIA (SPM), 2007", 50f, 624f),
                run("LAIN-LAIN", 320f, 640f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("Lesen Memandu Malaysia: D & B2", 320f, 624f)));

        var texts = parsedTexts(pdfPath);

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("BUTIRAN DIRI")
                .contains("Nombor I/C")
                .doesNotContain("TLS Transport"));
        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("PENGALAMAN PEKERJAAN")
                .contains("TLS Transport")
                .doesNotContain("BUTIRAN DIRI"));
        assertThat(texts)
                .anySatisfy(text -> assertThat(text).contains("PENDIDIKAN").doesNotContain("Lesen Memandu"));
    }

    @Test
    @DisplayName("sidebar language section stops before returning to the top of the main column")
    void sidebarLanguageSectionDoesNotSwallowMainColumnAfterColumnReset() throws Exception {
        var pdfPath = writePositionedPdf(List.of(
                run("CONTACT", 50f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("+6011-19822183", 50f, 700f),
                run("SKILLS", 50f, 660f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("Customer Relationship", 50f, 640f),
                run("LANGUAGES", 50f, 600f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("Bahasa Melayu", 50f, 580f),
                run("ASALLINA DAYA ANAK CHARLIE", 320f, 740f, 14f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("PROFILE", 320f, 700f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("Detail-oriented HR and administrative professional.", 320f, 680f),
                run("WORK EXPERIENCE", 320f, 640f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("Executive, Quality Assurance", 320f, 620f)));

        var texts = parsedTexts(pdfPath);

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("LANGUAGES")
                .contains("Bahasa Melayu")
                .doesNotContain("PROFILE")
                .doesNotContain("WORK EXPERIENCE"));
        assertThat(texts)
                .noneSatisfy(text -> assertThat(text).contains("LANGUAGES").contains("Detail-oriented HR"));
    }

    @Test
    @DisplayName("broken-letter section headings still stop sidebar semantic coalescing")
    void brokenLetterSectionHeadingsStopSidebarCoalescing() throws Exception {
        var pdfPath = writePositionedPdf(List.of(
                run("CONTACT", 50f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("+6011-19822183", 50f, 700f),
                run("EDUCATI ON", 50f, 660f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("2006-2007", 50f, 640f),
                run("SKI LLS", 50f, 600f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("Customer Relationship", 50f, 580f)));

        var texts = parsedTexts(pdfPath);

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("CONTACT")
                .contains("+6011-19822183")
                .doesNotContain("EDUCATI ON")
                .doesNotContain("Customer Relationship"));
        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("EDUCATI ON")
                .contains("2006-2007")
                .doesNotContain("CONTACT"));
        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("SKI LLS")
                .contains("Customer Relationship")
                .doesNotContain("2006-2007"));
    }

    @Test
    @DisplayName("same-row left profile text does not absorb right-column work text")
    void sameRowProfileTextDoesNotAbsorbRightColumnWorkText() throws Exception {
        var pdfPath = writePositionedPdf(List.of(
                run("MENGENAI SAYA", 50f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("PENGALAMAN KERJA", 320f, 720f, 12f, Standard14Fonts.FontName.HELVETICA_BOLD),
                run("Seorang yang teliti dalam melaksanakan tugasan", 50f, 700f),
                run("LTC Photostat Service", 380f, 700f),
                run("pentadbiran harian.", 50f, 684f),
                run("Pembantu Jualan", 380f, 684f)));

        var texts = parsedTexts(pdfPath);

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("MENGENAI SAYA")
                .contains("pentadbiran harian")
                .doesNotContain("LTC Photostat")
                .doesNotContain("Pembantu Jualan"));
        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("PENGALAMAN KERJA")
                .contains("LTC Photostat")
                .contains("Pembantu Jualan")
                .doesNotContain("Seorang yang teliti"));
    }

    @Test
    @DisplayName("semantic coalescing stops when a sidebar section returns to the main-column top")
    void semanticCoalescingStopsOnCrossColumnTopReset() {
        var blocks = List.of(
                block("LANGUAGES", BlockKind.HEADING, 50, 600, 160, 620),
                block("Bahasa Melayu", BlockKind.BODY, 50, 630, 170, 644),
                block("ASALLINA DAYA ANAK CHARLIE", BlockKind.HEADING, 320, 100, 560, 120),
                block("PROFILE", BlockKind.HEADING, 320, 140, 410, 158),
                block("Detail-oriented HR professional.", BlockKind.BODY, 320, 170, 560, 184));

        var texts = PdfSemanticSectionCoalescer.coalesce(blocks).stream()
                .map(PdfTextBlock::text)
                .toList();

        assertThat(texts).anySatisfy(text -> assertThat(text)
                .contains("LANGUAGES")
                .contains("Bahasa Melayu")
                .doesNotContain("ASALLINA")
                .doesNotContain("PROFILE"));
        assertThat(texts)
                .noneSatisfy(text -> assertThat(text).contains("LANGUAGES").contains("Detail-oriented HR"));
    }

    @Test
    @DisplayName("split Executive Summary title fragments reconstruct as one heading")
    void splitExecutiveSummaryFragmentsReconstructAsOneHeading() {
        var blocks = List.of(
                block("Executive", BlockKind.BODY, 320, 100, 392, 118),
                block("Summary", BlockKind.HEADING, 320, 122, 390, 140),
                block("Revenue expanded across all regions.", BlockKind.BODY, 320, 150, 560, 164));

        var coalesced = PdfSemanticSectionCoalescer.coalesce(blocks);

        assertThat(coalesced).hasSize(1);
        assertThat(coalesced.getFirst().kind()).isEqualTo(BlockKind.HEADING);
        assertThat(coalesced.getFirst().text()).isEqualTo("Executive Summary\nRevenue expanded across all regions.");
    }

    @Test
    @DisplayName("nearby-row two-column Executive and Summary blocks stay separate")
    void nearbyRowTwoColumnExecutiveAndSummaryBlocksStaySeparate() {
        var blocks = List.of(
                block("Executive", BlockKind.BODY, 50, 80, 122, 98),
                block("Summary", BlockKind.HEADING, 220, 100, 290, 118));

        var texts = PdfSemanticSectionCoalescer.coalesce(blocks).stream()
                .map(PdfTextBlock::text)
                .toList();

        assertThat(texts).containsExactly("Executive", "Summary");
    }

    private List<String> parsedTexts(Path pdfPath) throws ParseException {
        return PdfDocumentParser.parse(pdfPath).sections().stream()
                .map(section -> ((TextSection) section).text())
                .toList();
    }

    private Path writePositionedPdf(List<PositionedRun> runs) throws IOException {
        var path = tempDir.resolve("doc-" + System.nanoTime() + ".pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
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

    private static PositionedRun run(String text, float x, float y) {
        return run(text, x, y, 10f, Standard14Fonts.FontName.HELVETICA);
    }

    private static PositionedRun run(String text, float x, float y, float fontSize, Standard14Fonts.FontName fontName) {
        return new PositionedRun(text, x, y, fontSize, fontName);
    }

    private static PdfTextBlock block(String text, BlockKind kind, double x0, double y0, double x1, double y1) {
        return new PdfTextBlock(
                text,
                kind,
                new SourceLocation(1, 1, 1, Math.max(1, (int) text.lines().count()), 0),
                Optional.of(new BoundingBox(x0, y0, x1, y1)));
    }

    private record PositionedRun(String text, float x, float y, float fontSize, Standard14Fonts.FontName fontName) {}
}
