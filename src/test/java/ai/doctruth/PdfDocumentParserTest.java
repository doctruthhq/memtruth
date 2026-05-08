package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
 * non-blank page. Tables and figures arrive in Phase 3 (per the project roadmap).
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
            // Only the non-blank page becomes a section (matches AGENTS.md "engineering principles"
            // — empty TextSections are noise, not signal).
            assertThat(doc.sections()).hasSize(1);
            assertThat(((TextSection) doc.sections().get(0)).text()).contains("real content");
        }

        @Test
        @DisplayName("every emitted section has a non-null BlockKind on the happy-path PDFs")
        void everySectionHasKind() throws Exception {
            var pdfPath = writeMultiPagePdf(tempDir, List.of("page one", "page two", "page three"));

            var doc = PdfDocumentParser.parse(pdfPath);

            assertThat(doc.sections())
                    .allSatisfy(s -> assertThat(((TextSection) s).kind()).isNotNull());
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

    record Run(String text, float fontSize, float lineHeightAdvance) {}
}
