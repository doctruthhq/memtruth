package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ai.doctruth.spi.OcrEngine;
import ai.doctruth.spi.OcrPageResult;
import ai.doctruth.spi.OcrRegion;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for {@link PdfDocumentParser} — Layer 1 entry point.
 *
 * <p>Contract: parse a PDF file from disk into a {@link ParsedDocument} with one
 * {@link TextSection} per detected layout block (paragraph / heading / list) within each
 * non-blank page. Tables and figures are covered by separate parser surfaces.
 */
class PdfDocumentParserTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("a single-page single-line PDF produces ≥1 TextSection covering page 1")
        void singlePageProducesOneTextSection() throws Exception {
            var pdfPath = writeSinglePagePdf(tempDir, "Hello world from doctruth-java");

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.docId()).isNotBlank();
            assertThat(doc.metadata().sourceFilename()).endsWith(".pdf");
            assertThat(doc.metadata().pageCount()).isEqualTo(1);
            assertThat(doc.metadata().sourcePublishedAt()).isEmpty();
            assertThat(doc.sections()).isNotEmpty();
            assertThat(doc.sections()).hasSize(1);

            var section = doc.sections().get(0);
            assertThat(section).isInstanceOf(TextSection.class);
            var ts = (TextSection) section;
            assertThat(ts.text()).contains("Hello world from doctruth-java");
            assertThat(ts.location().pageStart()).isEqualTo(1);
            assertThat(ts.location().pageEnd()).isEqualTo(1);
            assertThat(ts.kind()).isNotNull();
            assertThat(ts.boundingBox()).hasValueSatisfying(bbox -> {
                assertThat(bbox.x0()).isLessThan(bbox.x1());
                assertThat(bbox.y0()).isLessThan(bbox.y1());
                assertThat(bbox.x0()).isBetween(0.0, 1000.0);
                assertThat(bbox.x1()).isBetween(0.0, 1000.0);
                assertThat(bbox.y0()).isBetween(0.0, 1000.0);
                assertThat(bbox.y1()).isBetween(0.0, 1000.0);
            });
        }

        @Test
        @DisplayName("a three-page PDF produces ≥3 TextSections with correct page anchors")
        void multiPageProducesPerPageSections() throws Exception {
            var pdfPath = writeMultiPagePdf(tempDir, List.of("first page text", "second page text", "third page text"));

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.metadata().pageCount()).isEqualTo(3);
            // One short single-line block per page → 3 sections (each kind defaulted via
            // detection).
            assertThat(doc.sections()).hasSize(3);

            for (int i = 0; i < 3; i++) {
                var section = doc.sections().get(i);
                assertThat(section).isInstanceOf(TextSection.class);
                var ts = (TextSection) section;
                assertThat(ts.location().pageStart()).isEqualTo(i + 1);
                assertThat(ts.location().pageEnd()).isEqualTo(i + 1);
                assertThat(ts.kind()).isNotNull();
            }

            assertThat(((TextSection) doc.sections().get(0)).text()).contains("first page");
            assertThat(((TextSection) doc.sections().get(1)).text()).contains("second page");
            assertThat(((TextSection) doc.sections().get(2)).text()).contains("third page");
        }

        @Test
        @DisplayName("docId is stable across two parses of the same file")
        void docIdIsStable() throws Exception {
            var pdfPath = writeSinglePagePdf(tempDir, "stable docId test");

            var docA = PdfDocumentParser.parse(pdfPath);
            var docB = PdfDocumentParser.parse(pdfPath);

            assertThat(docA.docId()).isEqualTo(docB.docId());
        }

        @Test
        @DisplayName("a blank-content PDF page is omitted (skipped) rather than producing an empty TextSection")
        void blankPageOmitted() throws Exception {
            // Write a PDF where one page has whitespace-only content
            var pdfPath = writeMultiPagePdf(tempDir, List.of("real content", "   "));

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.metadata().pageCount()).isEqualTo(2);
            // Only the non-blank page becomes a section (matches CONTRIBUTING.md "engineering principles"
            // — empty TextSections are noise, not signal).
            assertThat(doc.sections()).hasSize(1);
            assertThat(((TextSection) doc.sections().get(0)).text()).contains("real content");
        }

        @Test
        @DisplayName("low-text PDF pages are routed to OCR before DocTruth section assembly")
        void lowTextPageRoutesToOcrBeforeSectionAssembly() throws Exception {
            var pdfPath = writeBlankPagePdf(tempDir);
            var calls = new AtomicInteger();
            OcrEngine ocr = (BufferedImage pageImage, int pageNumber) -> {
                calls.incrementAndGet();
                return new OcrPageResult(
                        "OCR recovered resume text",
                        0.91,
                        List.of(new OcrRegion("OCR recovered resume text", 10, 20, 120, 30, 0.91)),
                        pageNumber);
            };

            var doc = PdfDocumentParser.parse(pdfPath, ocr);

            assertThat(calls).hasValue(1);
            assertThat(doc.sections()).hasSize(1);
            var section = (TextSection) doc.sections().get(0);
            assertThat(section.text()).isEqualTo("OCR recovered resume text");
            assertThat(section.location().pageStart()).isEqualTo(1);
            assertThat(section.boundingBox()).hasValueSatisfying(box -> {
                assertThat(box.x0()).isGreaterThanOrEqualTo(0.0);
                assertThat(box.x1()).isLessThanOrEqualTo(1000.0);
                assertThat(box.y0()).isGreaterThanOrEqualTo(0.0);
                assertThat(box.y1()).isLessThanOrEqualTo(1000.0);
            });
        }

        @Test
        @DisplayName("OCR page routing preserves region-level reading order and bounding boxes")
        void lowTextPageRoutesOcrRegionsAsSeparateSections() throws Exception {
            var pdfPath = writeBlankPagePdf(tempDir);
            OcrEngine ocr = (BufferedImage pageImage, int pageNumber) -> new OcrPageResult(
                    "second visual line\nfirst visual line",
                    0.91,
                    List.of(
                            new OcrRegion("second visual line", 50, 160, 220, 30, 0.91),
                            new OcrRegion("first visual line", 50, 80, 200, 30, 0.93)),
                    pageNumber);

            var doc = PdfDocumentParser.parse(pdfPath, ocr);

            assertThat(doc.sections()).hasSize(2);
            assertThat(((TextSection) doc.sections().get(0)).text()).isEqualTo("second visual line");
            assertThat(((TextSection) doc.sections().get(1)).text()).isEqualTo("first visual line");
            var firstBox = ((TextSection) doc.sections().get(0)).boundingBox().orElseThrow();
            var secondBox = ((TextSection) doc.sections().get(1)).boundingBox().orElseThrow();
            assertThat(firstBox.y0()).isGreaterThan(secondBox.y0());
        }

        @Test
        @DisplayName("OCR region source locations are compact after blank regions and multi-line regions")
        void lowTextPageRoutesOcrRegionsWithCompactLineRanges() throws Exception {
            var pdfPath = writeBlankPagePdf(tempDir);
            OcrEngine ocr = (BufferedImage pageImage, int pageNumber) -> new OcrPageResult(
                    "first line\nsecond line\nthird line",
                    0.91,
                    List.of(
                            new OcrRegion("first line\nsecond line", 50, 80, 200, 60, 0.93),
                            new OcrRegion("   ", 50, 150, 200, 30, 0.5),
                            new OcrRegion("third line", 50, 190, 200, 30, 0.91)),
                    pageNumber);

            var doc = PdfDocumentParser.parse(pdfPath, ocr);

            assertThat(doc.sections()).hasSize(2);
            var first = (TextSection) doc.sections().get(0);
            var second = (TextSection) doc.sections().get(1);
            assertThat(first.location().lineStart()).isEqualTo(1);
            assertThat(first.location().lineEnd()).isEqualTo(2);
            assertThat(second.location().lineStart()).isEqualTo(3);
            assertThat(second.location().lineEnd()).isEqualTo(3);
        }

        @Test
        @DisplayName("OCR page routing falls back to one aggregate section when no regions are returned")
        void lowTextPageRoutesOcrTextWithoutRegionsAsAggregateSection() throws Exception {
            var pdfPath = writeBlankPagePdf(tempDir);
            OcrEngine ocr = (BufferedImage pageImage, int pageNumber) -> new OcrPageResult(
                    "OCR recovered page text",
                    0.91,
                    List.of(),
                    pageNumber);

            var doc = PdfDocumentParser.parse(pdfPath, ocr);

            assertThat(doc.sections()).hasSize(1);
            var section = (TextSection) doc.sections().getFirst();
            assertThat(section.text()).isEqualTo("OCR recovered page text");
            assertThat(section.location().lineStart()).isEqualTo(1);
            assertThat(section.location().lineEnd()).isEqualTo(1);
            assertThat(section.boundingBox()).contains(new BoundingBox(0, 0, 1000, 1000));
        }

        @Test
        @DisplayName("usable text-layer PDF pages do not call OCR")
        void usableTextLayerPagesDoNotCallOcr() throws Exception {
            var pdfPath = writeSinglePagePdf(
                    tempDir,
                    "This PDF has enough selectable text for DocTruth parsing without OCR routing.");
            var calls = new AtomicInteger();
            OcrEngine ocr = (BufferedImage pageImage, int pageNumber) -> {
                calls.incrementAndGet();
                return new OcrPageResult("should not be used", 0.5, List.of(), pageNumber);
            };

            var doc = PdfDocumentParser.parse(pdfPath, ocr);

            assertThat(calls).hasValue(0);
            assertThat(doc.sections()).hasSize(1);
            assertThat(((TextSection) doc.sections().get(0)).text())
                    .contains("This PDF has enough selectable text")
                    .doesNotContain("should not be used");
        }

        @Test
        @DisplayName("every emitted section has a non-null BlockKind on the happy-path PDFs")
        void everySectionHasKind() throws Exception {
            var pdfPath = writeMultiPagePdf(tempDir, List.of("page one", "page two", "page three"));

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.sections())
                    .allSatisfy(s -> assertThat(((TextSection) s).kind()).isNotNull());
        }

        @Test
        @DisplayName("standalone table captions adjacent to a table become FigureSection caption blocks")
        void adjacentTableCaptionBecomesCaptionSection() throws Exception {
            var pdfPath = writeCaptionedTablePdf(tempDir);

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.sections()).hasSize(3);
            assertThat(doc.sections().get(0)).isInstanceOf(TextSection.class);
            assertThat(doc.sections().get(1)).isInstanceOf(FigureSection.class);
            assertThat(doc.sections().get(2)).isInstanceOf(TableSection.class);
            assertThat(((FigureSection) doc.sections().get(1)).caption())
                    .isEqualTo("Table 1. Quarterly revenue by region");
        }

        @Test
        @DisplayName("caption-like body sentences are not promoted without standalone caption shape")
        void captionLikeBodySentenceStaysTextSection() throws Exception {
            var pdfPath = writeSinglePagePdf(tempDir, "Figure 4.3 illustrates the process but this is body text.");

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.sections()).hasSize(1);
            assertThat(doc.sections().getFirst()).isInstanceOf(TextSection.class);
            assertThat(((TextSection) doc.sections().getFirst()).text())
                    .contains("Figure 4.3 illustrates the process");
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("null pdfPath throws NullPointerException with 'pdfPath' in the message")
        void nullPath() {
            assertThatNullPointerException()
                    .isThrownBy(() -> PdfDocumentParser.parse(null))
                    .withMessageContaining("pdfPath");
        }

        @Test
        @DisplayName("a nonexistent file throws ParseException with errorCode and sourceName populated")
        void nonexistentFile() {
            var missing = tempDir.resolve("does-not-exist-" + System.nanoTime() + ".pdf");

            assertThatThrownBy(() -> PdfDocumentParser.parse(missing))
                    .isInstanceOf(ParseException.class)
                    .satisfies(e -> {
                        var pe = (ParseException) e;
                        assertThat(pe.errorCode()).isNotBlank();
                        assertThat(pe.sourceName()).contains("does-not-exist");
                        assertThat(pe.pageNumber()).isEmpty();
                    });
        }

        @Test
        @DisplayName("a non-PDF file (plain text with .pdf extension) throws ParseException with cause")
        void nonPdfFile() throws IOException {
            var notPdf = tempDir.resolve("not-actually-pdf.pdf");
            Files.writeString(notPdf, "This is plain text, not a PDF binary.");

            assertThatThrownBy(() -> PdfDocumentParser.parse(notPdf))
                    .isInstanceOf(ParseException.class)
                    .satisfies(e -> {
                        var pe = (ParseException) e;
                        assertThat(pe.errorCode()).isNotBlank();
                        assertThat(pe.getCause()).isInstanceOf(IOException.class);
                    });
        }

        @Test
        @DisplayName("a directory passed where a file is expected throws ParseException")
        void directoryRejected() {
            assertThatThrownBy(() -> PdfDocumentParser.parse(tempDir)).isInstanceOf(ParseException.class);
        }
    }

    @Nested
    @DisplayName("layout blocks")
    class LayoutBlocks {

        @Test
        @DisplayName("three lines of body text packed close together collapse into one BODY block")
        void singleBlockMultipleLines() throws Exception {
            var pdfPath = writeStructuredPdf(
                    tempDir,
                    List.of(
                            new Run("First line of one paragraph.", 12f, 14f),
                            new Run("Second line of the same paragraph.", 12f, 14f),
                            new Run("Third line of the same paragraph.", 12f, 14f)));

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.sections()).hasSize(1);
            var ts = (TextSection) doc.sections().get(0);
            assertThat(ts.kind()).isEqualTo(BlockKind.BODY);
            assertThat(ts.text()).contains("First line").contains("Third line");
        }

        @Test
        @DisplayName("a 16pt heading followed (after a wide gap) by 12pt body splits into HEADING + BODY")
        void headingThenBody() throws Exception {
            var pdfPath = writeStructuredPdf(
                    tempDir,
                    List.of(
                            new Run("Section Heading", 16f, 60f),
                            new Run("Body paragraph following the heading.", 12f, 14f)));

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.sections()).hasSize(2);
            var first = (TextSection) doc.sections().get(0);
            var second = (TextSection) doc.sections().get(1);
            assertThat(first.kind()).isEqualTo(BlockKind.HEADING);
            assertThat(first.text()).contains("Section Heading");
            assertThat(second.kind()).isEqualTo(BlockKind.BODY);
            assertThat(second.text()).contains("Body paragraph");
        }

        @Test
        @DisplayName("an all-caps short line at body font-size is classified as HEADING")
        void allCapsShortHeading() throws Exception {
            var pdfPath = writeStructuredPdf(
                    tempDir,
                    List.of(
                            new Run("MAKLUMAT PERIBADI", 12f, 60f),
                            new Run("Some body content beneath the heading.", 12f, 14f)));

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.sections()).hasSize(2);
            var first = (TextSection) doc.sections().get(0);
            assertThat(first.kind()).isEqualTo(BlockKind.HEADING);
            assertThat(first.text()).contains("MAKLUMAT PERIBADI");
            assertThat(((TextSection) doc.sections().get(1)).kind()).isEqualTo(BlockKind.BODY);
        }

        @Test
        @DisplayName("a bulleted line starting with a bullet glyph is classified as LIST")
        void bulletedList() throws Exception {
            var pdfPath = writeStructuredPdf(tempDir, List.of(new Run("- a hyphen list item", 12f, 14f)));

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.sections()).hasSize(1);
            var ts = (TextSection) doc.sections().get(0);
            assertThat(ts.kind()).isEqualTo(BlockKind.LIST);
        }

        @Test
        @DisplayName("a numbered line starting '1. ' is classified as LIST")
        void numberedList() throws Exception {
            var pdfPath = writeStructuredPdf(tempDir, List.of(new Run("1. First numbered item", 12f, 14f)));

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.sections()).hasSize(1);
            var ts = (TextSection) doc.sections().get(0);
            assertThat(ts.kind()).isEqualTo(BlockKind.LIST);
        }

        @Test
        @DisplayName("two text runs separated by > 2× line-height become two distinct blocks")
        void gapBasedBlockBreak() throws Exception {
            var pdfPath = writeStructuredPdf(
                    tempDir,
                    List.of(
                            new Run("Block one paragraph text.", 12f, 60f),
                            new Run("Block two paragraph text.", 12f, 14f)));

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.sections()).hasSize(2);
            assertThat(((TextSection) doc.sections().get(0)).text()).contains("Block one");
            assertThat(((TextSection) doc.sections().get(1)).text()).contains("Block two");
        }

        @Test
        @DisplayName("classify(): bullet-prefix → LIST")
        void classifyBullet() {
            assertThat(PdfDocumentParser.classify("• a bulleted item", 12.0, 12.0))
                    .isEqualTo(BlockKind.LIST);
        }

        @Test
        @DisplayName("classify(): numbered prefix '1. ' → LIST")
        void classifyNumbered() {
            assertThat(PdfDocumentParser.classify("1. first item", 12.0, 12.0)).isEqualTo(BlockKind.LIST);
        }

        @Test
        @DisplayName("classify(): avg height 1.5× page median → HEADING")
        void classifyHeadingBySize() {
            assertThat(PdfDocumentParser.classify("Some Title", 18.0, 12.0)).isEqualTo(BlockKind.HEADING);
        }

        @Test
        @DisplayName("classify(): key-value field lines stay BODY even when the line is visually taller")
        void classifyKeyValueFieldStaysBody() {
            assertThat(PdfDocumentParser.classify("Party A: Acme Industrial Materials Pty Ltd", 18.0, 12.0))
                    .isEqualTo(BlockKind.BODY);
        }

        @Test
        @DisplayName("classify(): 'MAKLUMAT PERIBADI' at body size → HEADING via all-caps rule")
        void classifyHeadingByAllCaps() {
            assertThat(PdfDocumentParser.classify("MAKLUMAT PERIBADI", 12.0, 12.0))
                    .isEqualTo(BlockKind.HEADING);
        }

        @Test
        @DisplayName("classify(): a phone number '0182186889' is digit-heavy and stays BODY, not HEADING")
        void classifyDigitHeavyStaysBody() {
            assertThat(PdfDocumentParser.classify("0182186889", 12.0, 12.0)).isEqualTo(BlockKind.BODY);
        }

        @Test
        @DisplayName("classify(): a normal sentence at body size → BODY")
        void classifyBody() {
            assertThat(PdfDocumentParser.classify("This is the body of the paragraph in mixed case.", 12.0, 12.0))
                    .isEqualTo(BlockKind.BODY);
        }

        @Test
        @DisplayName("classify(): blank text → OTHER")
        void classifyBlank() {
            assertThat(PdfDocumentParser.classify("", 12.0, 12.0)).isEqualTo(BlockKind.OTHER);
            assertThat(PdfDocumentParser.classify("   \n  ", 12.0, 12.0)).isEqualTo(BlockKind.OTHER);
        }
    }

    // --- helpers ---------------------------------------------------------

    private static Path writeSinglePagePdf(Path dir, String text) throws IOException {
        return writeMultiPagePdf(dir, List.of(text));
    }

    private static Path writeBlankPagePdf(Path dir) throws IOException {
        var path = dir.resolve("blank-" + System.nanoTime() + ".pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(path.toFile());
        }
        return path;
    }

    private static Path writeMultiPagePdf(Path dir, List<String> pageTexts) throws IOException {
        var path = dir.resolve("doc-" + System.nanoTime() + ".pdf");
        try (var pdf = new PDDocument()) {
            for (var pageText : pageTexts) {
                var page = new PDPage();
                pdf.addPage(page);
                try (var cs = new PDPageContentStream(pdf, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(pageText);
                    cs.endText();
                }
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    /** Programmatic-PDF helper that emits one Run per call with controllable font size +
     *  vertical advance. Use {@code lineHeightAdvance} ≈ 14 for tight body lines and
     *  {@code 50+} to force a layout-block break. */
    private static Path writeStructuredPdf(Path dir, List<Run> runs) throws IOException {
        var path = dir.resolve("doc-" + System.nanoTime() + ".pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
                float yCursor = 720f;
                for (var run : runs) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), run.fontSize());
                    cs.newLineAtOffset(50f, yCursor);
                    cs.showText(run.text());
                    cs.endText();
                    yCursor -= run.lineHeightAdvance();
                }
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private static Path writeCaptionedTablePdf(Path dir) throws IOException {
        var path = dir.resolve("captioned-table-" + System.nanoTime() + ".pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
                writeText(cs, "Revenue overview", 50, 750);
                writeText(cs, "Table 1. Quarterly revenue by region", 72, 705);
                drawLine(cs, 72, 680, 360, 680);
                drawLine(cs, 72, 640, 360, 640);
                drawLine(cs, 72, 600, 360, 600);
                drawLine(cs, 72, 680, 72, 600);
                drawLine(cs, 216, 680, 216, 600);
                drawLine(cs, 360, 680, 360, 600);
                writeText(cs, "Region", 100, 655);
                writeText(cs, "Revenue", 245, 655);
                writeText(cs, "North", 100, 615);
                writeText(cs, "$10M", 245, 615);
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private static void writeText(PDPageContentStream stream, String text, float x, float y) throws IOException {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }

    private static void drawLine(PDPageContentStream stream, float x0, float y0, float x1, float y1)
            throws IOException {
        stream.moveTo(x0, y0);
        stream.lineTo(x1, y1);
        stream.stroke();
    }

    record Run(String text, float fontSize, float lineHeightAdvance) {}
}
