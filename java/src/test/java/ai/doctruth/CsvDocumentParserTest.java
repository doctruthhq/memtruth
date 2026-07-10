package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.RuntimeJsonMappingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for {@link CsvDocumentParser} — Layer 1 entry point for CSV.
 *
 * <p>v0.1.0-alpha contract: parse a CSV file from disk into a {@link ParsedDocument} with
 * exactly one {@link TableSection} representing every CSV row as a list of string cells.
 * The whole CSV is treated as a single logical "page" (rows map to lines). Comma-only
 * delimiter; no header detection (every row is data — caller decides if row 0 is a header).
 */
class CsvDocumentParserTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("a 3-row 2-column CSV produces one TableSection with 3 rows of 2 cells")
        void threeRowProducesOneTableSection() throws Exception {
            var csvPath = writeCsv(tempDir, "a,1\nb,2\nc,3", StandardCharsets.UTF_8);

            var doc = CsvDocumentParser.parse(csvPath);

            assertThat(doc.docId()).startsWith("sha256:");
            assertThat(doc.metadata().sourceFilename()).endsWith(".csv");
            assertThat(doc.metadata().pageCount()).isEqualTo(1);
            assertThat(doc.metadata().sourcePublishedAt()).isEmpty();
            assertThat(doc.sections()).hasSize(1);

            var section = doc.sections().get(0);
            assertThat(section).isInstanceOf(TableSection.class);
            var ts = (TableSection) section;
            assertThat(ts.rows()).hasSize(3);
            assertThat(ts.rows().get(0)).containsExactly("a", "1");
            assertThat(ts.rows().get(1)).containsExactly("b", "2");
            assertThat(ts.rows().get(2)).containsExactly("c", "3");
            assertThat(ts.location().pageStart()).isEqualTo(1);
            assertThat(ts.location().pageEnd()).isEqualTo(1);
            assertThat(ts.location().lineStart()).isEqualTo(1);
            assertThat(ts.location().lineEnd()).isEqualTo(3);
        }

        @Test
        @DisplayName("an empty CSV file produces zero sections (matches PDF blank-page rule)")
        void emptyCsvProducesZeroSections() throws Exception {
            var csvPath = writeCsv(tempDir, "", StandardCharsets.UTF_8);

            var doc = CsvDocumentParser.parse(csvPath);

            assertThat(doc.metadata().pageCount()).isEqualTo(1);
            assertThat(doc.sections()).isEmpty();
        }

        @Test
        @DisplayName("a quoted cell containing a comma is preserved as a single cell")
        void quotedCellWithCommaPreserved() throws Exception {
            var csvPath = writeCsv(tempDir, "\"hello, world\",2\n", StandardCharsets.UTF_8);

            var doc = CsvDocumentParser.parse(csvPath);

            assertThat(doc.sections()).hasSize(1);
            var ts = (TableSection) doc.sections().get(0);
            assertThat(ts.rows()).hasSize(1);
            assertThat(ts.rows().get(0)).containsExactly("hello, world", "2");
        }

        @Test
        @DisplayName("a quoted cell containing an embedded newline counts as one logical row")
        void quotedCellWithNewlinePreserved() throws Exception {
            var csvPath = writeCsv(tempDir, "\"a\nb\",2\n", StandardCharsets.UTF_8);

            var doc = CsvDocumentParser.parse(csvPath);

            assertThat(doc.sections()).hasSize(1);
            var ts = (TableSection) doc.sections().get(0);
            assertThat(ts.rows()).hasSize(1);
            assertThat(ts.rows().get(0)).containsExactly("a\nb", "2");
        }

        @Test
        @DisplayName("docId is stable across two byte-identical files")
        void docIdStableForIdenticalBytes() throws Exception {
            var pathA = writeCsv(tempDir, "a,1\nb,2\n", StandardCharsets.UTF_8);
            var pathB = tempDir.resolve("renamed-elsewhere.csv");
            Files.copy(pathA, pathB);

            var docA = CsvDocumentParser.parse(pathA);
            var docB = CsvDocumentParser.parse(pathB);

            assertThat(docA.docId()).isEqualTo(docB.docId());
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("null csvPath throws NullPointerException with 'csvPath' in the message")
        void nullPath() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CsvDocumentParser.parse(null))
                    .withMessageContaining("csvPath");
        }

        @Test
        @DisplayName("[APP 11.1] docId does NOT echo the file path or filename — content-addressable, no PII leak")
        void docIdDoesNotLeakFilePath() throws Exception {
            var sensitiveDir = Files.createDirectory(tempDir.resolve("customer-99999-PII"));
            var csvPath = sensitiveDir.resolve("medical-claim-jane-doe.csv");
            Files.writeString(csvPath, "a,1\nb,2\n", StandardCharsets.UTF_8);

            var doc = CsvDocumentParser.parse(csvPath);

            assertThat(doc.docId())
                    .doesNotContain("customer-99999-PII")
                    .doesNotContain("medical-claim-jane-doe")
                    .doesNotContain(sensitiveDir.toString())
                    .doesNotContain(csvPath.toString());
            assertThat(doc.docId()).startsWith("sha256:").hasSize("sha256:".length() + 64);
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("a nonexistent file throws ParseException with CSV_FILE_NOT_FOUND")
        void nonexistentFile() {
            var missing = tempDir.resolve("does-not-exist-" + System.nanoTime() + ".csv");

            assertThatThrownBy(() -> CsvDocumentParser.parse(missing))
                    .isInstanceOf(ParseException.class)
                    .satisfies(e -> {
                        var pe = (ParseException) e;
                        assertThat(pe.errorCode()).isEqualTo("CSV_FILE_NOT_FOUND");
                        assertThat(pe.sourceName()).contains("does-not-exist");
                        assertThat(pe.pageNumber()).isEmpty();
                    });
        }

        @Test
        @DisplayName("a directory passed where a file is expected throws ParseException with CSV_FILE_NOT_FOUND")
        void directoryRejected() {
            assertThatThrownBy(() -> CsvDocumentParser.parse(tempDir))
                    .isInstanceOf(ParseException.class)
                    .satisfies(e -> {
                        var pe = (ParseException) e;
                        assertThat(pe.errorCode()).isEqualTo("CSV_FILE_NOT_FOUND");
                    });
        }

        @Test
        @DisplayName("malformed CSV throws ParseException with CSV_PARSE_FAILED and preserves cause")
        void malformedCsvRejected() throws Exception {
            var csvPath = writeCsv(tempDir, "\"unterminated\nnext,row", StandardCharsets.UTF_8);

            assertThatThrownBy(() -> CsvDocumentParser.parse(csvPath))
                    .isInstanceOf(ParseException.class)
                    .satisfies(e -> {
                        var pe = (ParseException) e;
                        assertThat(pe.errorCode()).isEqualTo("CSV_PARSE_FAILED");
                        assertThat(pe.sourceName()).isEqualTo(csvPath.toString());
                        assertThat(pe.pageNumber()).isEmpty();
                        assertThat(pe).hasCauseInstanceOf(RuntimeJsonMappingException.class);
                    });
        }
    }

    @Nested
    @DisplayName("encoding")
    class Encoding {

        @Test
        @DisplayName("a UTF-8 CSV with multi-byte characters parses successfully on the first attempt")
        void utf8MultiByteParsed() throws Exception {
            var csvPath = writeCsv(tempDir, "héllo,1\n中文,2\n", StandardCharsets.UTF_8);

            var doc = CsvDocumentParser.parse(csvPath);

            assertThat(doc.sections()).hasSize(1);
            var ts = (TableSection) doc.sections().get(0);
            assertThat(ts.rows()).hasSize(2);
            assertThat(ts.rows().get(0)).containsExactly("héllo", "1");
            assertThat(ts.rows().get(1)).containsExactly("中文", "2");
        }

        @Test
        @DisplayName("a Latin-1 CSV with bytes invalid under UTF-8 falls back and parses successfully")
        void latin1FallbackParsed() throws Exception {
            // 'café' written in Latin-1 produces the byte sequence 63 61 66 E9 — 0xE9 alone is
            // not a valid UTF-8 start byte (it implies a 3-byte sequence but no continuation
            // bytes follow in this content), so a strict UTF-8 reader raises MalformedInputException.
            var csvPath = writeCsv(tempDir, "café,1\n", Charset.forName("ISO-8859-1"));

            var doc = CsvDocumentParser.parse(csvPath);

            assertThat(doc.sections()).hasSize(1);
            var ts = (TableSection) doc.sections().get(0);
            assertThat(ts.rows()).hasSize(1);
            assertThat(ts.rows().get(0)).hasSize(2);
            // The cell content contains the high-bit character (rendered through ISO-8859-1).
            assertThat(ts.rows().get(0).get(0)).contains("caf");
            assertThat(ts.rows().get(0).get(0)).hasSize(4);
            assertThat(ts.rows().get(0).get(1)).isEqualTo("1");
        }
    }

    // --- helpers ---------------------------------------------------------

    private static Path writeCsv(Path dir, String content, Charset charset) throws IOException {
        var path = dir.resolve("doc-" + System.nanoTime() + ".csv");
        Files.writeString(path, content, charset);
        return path;
    }
}
