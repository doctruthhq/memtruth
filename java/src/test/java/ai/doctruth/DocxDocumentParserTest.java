package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for {@link DocxDocumentParser} — Layer 1 entry point for DOCX.
 *
 * <p>v0.1.0-alpha contract: parse a DOCX file from disk into a {@link ParsedDocument} with
 * one {@link TextSection} per non-blank paragraph and one {@link TableSection} per table.
 * DOCX is logically continuous (no native page numbers); every section uses synthetic
 * {@code pageNumber = 1} until layout-aware parsing is supported.
 */
class DocxDocumentParserTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("a single-paragraph DOCX produces a ParsedDocument with one TextSection")
        void singleParagraphProducesOneTextSection() throws Exception {
            var docxPath = writeDocxWithParagraphs(tempDir, List.of("Hello world from doctruth-java"));

            var doc = DocxDocumentParser.parse(docxPath);

            assertThat(doc.docId()).startsWith("sha256:");
            assertThat(doc.metadata().sourceFilename()).endsWith(".docx");
            assertThat(doc.metadata().pageCount()).isEqualTo(1);
            assertThat(doc.metadata().sourcePublishedAt()).isEmpty();
            assertThat(doc.sections()).hasSize(1);

            var section = doc.sections().get(0);
            assertThat(section).isInstanceOf(TextSection.class);
            var ts = (TextSection) section;
            assertThat(ts.text()).contains("Hello world from doctruth-java");
            assertThat(ts.location().pageStart()).isEqualTo(1);
            assertThat(ts.location().pageEnd()).isEqualTo(1);
        }

        @Test
        @DisplayName("a multi-paragraph DOCX produces N TextSections in document order")
        void multiParagraphProducesPerParagraphSections() throws Exception {
            var docxPath = writeDocxWithParagraphs(
                    tempDir, List.of("first paragraph text", "second paragraph text", "third paragraph text"));

            var doc = DocxDocumentParser.parse(docxPath);

            assertThat(doc.sections()).hasSize(3);
            assertThat(doc.sections()).allMatch(s -> s instanceof TextSection);

            assertThat(((TextSection) doc.sections().get(0)).text()).contains("first paragraph");
            assertThat(((TextSection) doc.sections().get(1)).text()).contains("second paragraph");
            assertThat(((TextSection) doc.sections().get(2)).text()).contains("third paragraph");
        }

        @Test
        @DisplayName("a blank paragraph in the middle is omitted (matches PDF blank-page rule)")
        void blankParagraphOmitted() throws Exception {
            var docxPath = writeDocxWithParagraphs(tempDir, List.of("real content", "   ", "more content"));

            var doc = DocxDocumentParser.parse(docxPath);

            // Only the two non-blank paragraphs become sections — empty TextSections are
            // noise, not signal (CONTRIBUTING.md §2 "no silent failures meaningless content").
            assertThat(doc.sections()).hasSize(2);
            assertThat(((TextSection) doc.sections().get(0)).text()).contains("real content");
            assertThat(((TextSection) doc.sections().get(1)).text()).contains("more content");
        }

        @Test
        @DisplayName("docId is stable across two parses of the same byte-identical file")
        void docIdIsStableForIdenticalBytes() throws Exception {
            var pathA = writeDocxWithParagraphs(tempDir, List.of("stable docId test"));
            var pathB = tempDir.resolve("renamed-elsewhere.docx");
            Files.copy(pathA, pathB);

            var docA = DocxDocumentParser.parse(pathA);
            var docB = DocxDocumentParser.parse(pathB);

            assertThat(docA.docId()).isEqualTo(docB.docId());
        }

        @Test
        @DisplayName("docId differs for different content")
        void docIdDiffersForDifferentContent() throws Exception {
            var pathA = writeDocxWithParagraphs(tempDir, List.of("alpha content"));
            var pathB = writeDocxWithParagraphs(tempDir, List.of("beta content"));

            var docA = DocxDocumentParser.parse(pathA);
            var docB = DocxDocumentParser.parse(pathB);

            assertThat(docA.docId()).isNotEqualTo(docB.docId());
        }

        @Test
        @DisplayName("a DOCX containing a table produces a TableSection with the table's cell text")
        void tableProducesTableSection() throws Exception {
            var docxPath = tempDir.resolve("with-table-" + System.nanoTime() + ".docx");
            try (var docx = new XWPFDocument()) {
                var p = docx.createParagraph();
                p.createRun().setText("intro paragraph");
                XWPFTable table = docx.createTable(2, 2);
                table.getRow(0).getCell(0).setText("r0c0");
                table.getRow(0).getCell(1).setText("r0c1");
                table.getRow(1).getCell(0).setText("r1c0");
                table.getRow(1).getCell(1).setText("r1c1");
                try (var out = Files.newOutputStream(docxPath)) {
                    docx.write(out);
                }
            }

            var doc = DocxDocumentParser.parse(docxPath);

            assertThat(doc.sections()).anyMatch(s -> s instanceof TableSection);
            var tableSection = (TableSection) doc.sections().stream()
                    .filter(s -> s instanceof TableSection)
                    .findFirst()
                    .orElseThrow();
            assertThat(tableSection.rows()).hasSize(2);
            assertThat(tableSection.rows().get(0)).contains("r0c0", "r0c1");
            assertThat(tableSection.rows().get(1)).contains("r1c0", "r1c1");
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("null docxPath throws NullPointerException with 'docxPath' in the message")
        void nullPath() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DocxDocumentParser.parse(null))
                    .withMessageContaining("docxPath");
        }

        @Test
        @DisplayName("[APP 11.1] docId does NOT echo the file path or filename — content-addressable, no PII leak")
        void docIdDoesNotLeakFilePath() throws Exception {
            var sensitiveDir = Files.createDirectory(tempDir.resolve("customer-99999-PII"));
            var docxPath = sensitiveDir.resolve("medical-claim-jane-doe.docx");
            try (var docx = new XWPFDocument()) {
                var p = docx.createParagraph();
                p.createRun().setText("harmless content");
                try (var out = Files.newOutputStream(docxPath)) {
                    docx.write(out);
                }
            }

            var doc = DocxDocumentParser.parse(docxPath);

            assertThat(doc.docId())
                    .doesNotContain("customer-99999-PII")
                    .doesNotContain("medical-claim-jane-doe")
                    .doesNotContain(sensitiveDir.toString())
                    .doesNotContain(docxPath.toString());
            assertThat(doc.docId()).startsWith("sha256:").hasSize("sha256:".length() + 64);
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("a nonexistent file throws ParseException with DOCX_FILE_NOT_FOUND")
        void nonexistentFile() {
            var missing = tempDir.resolve("does-not-exist-" + System.nanoTime() + ".docx");

            assertThatThrownBy(() -> DocxDocumentParser.parse(missing))
                    .isInstanceOf(ParseException.class)
                    .satisfies(e -> {
                        var pe = (ParseException) e;
                        assertThat(pe.errorCode()).isEqualTo("DOCX_FILE_NOT_FOUND");
                        assertThat(pe.sourceName()).contains("does-not-exist");
                        assertThat(pe.pageNumber()).isEmpty();
                    });
        }

        @Test
        @DisplayName("a non-DOCX file (plain text with .docx extension) throws ParseException with cause")
        void nonDocxFile() throws IOException {
            var notDocx = tempDir.resolve("not-actually-docx.docx");
            Files.writeString(notDocx, "This is plain text, not a DOCX zip.");

            assertThatThrownBy(() -> DocxDocumentParser.parse(notDocx))
                    .isInstanceOf(ParseException.class)
                    .satisfies(e -> {
                        var pe = (ParseException) e;
                        assertThat(pe.errorCode()).isEqualTo("DOCX_PARSE_FAILED");
                        assertThat(pe.getCause()).isInstanceOf(IOException.class);
                    });
        }

        @Test
        @DisplayName("a directory passed where a file is expected throws ParseException")
        void directoryRejected() {
            assertThatThrownBy(() -> DocxDocumentParser.parse(tempDir))
                    .isInstanceOf(ParseException.class)
                    .satisfies(e -> {
                        var pe = (ParseException) e;
                        assertThat(pe.errorCode()).isEqualTo("DOCX_FILE_NOT_FOUND");
                    });
        }
    }

    // --- helpers ---------------------------------------------------------

    private static Path writeDocxWithParagraphs(Path dir, List<String> paragraphs) throws IOException {
        var path = dir.resolve("doc-" + System.nanoTime() + ".docx");
        try (var docx = new XWPFDocument()) {
            for (var text : paragraphs) {
                var p = docx.createParagraph();
                var r = p.createRun();
                r.setText(text);
            }
            try (var out = Files.newOutputStream(path)) {
                docx.write(out);
            }
        }
        return path;
    }
}
