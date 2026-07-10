package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for {@link XlsxDocumentParser} — Layer 1 entry point for XLSX.
 *
 * <p>v0.1.0-alpha contract: parse an XLSX file from disk into a {@link ParsedDocument} with
 * one {@link TableSection} per non-empty sheet. Sheets become "pages" (1-indexed) and rows
 * become "lines" (1-indexed) in the {@link SourceLocation} analogy.
 */
class XlsxDocumentParserTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("a single-sheet 3x3 grid produces one TableSection with three rows of three cells")
        void singleSheetProducesOneTableSection() throws Exception {
            var rows = List.of(List.of("a1", "b1", "c1"), List.of("a2", "b2", "c2"), List.of("a3", "b3", "c3"));
            var xlsxPath = writeXlsx(tempDir, mapOf("Sheet1", rows));

            var doc = XlsxDocumentParser.parse(xlsxPath);

            assertThat(doc.docId()).startsWith("sha256:");
            assertThat(doc.metadata().sourceFilename()).endsWith(".xlsx");
            assertThat(doc.metadata().pageCount()).isEqualTo(1);
            assertThat(doc.metadata().sourcePublishedAt()).isEmpty();
            assertThat(doc.sections()).hasSize(1);

            var section = doc.sections().get(0);
            assertThat(section).isInstanceOf(TableSection.class);
            var ts = (TableSection) section;
            assertThat(ts.rows()).hasSize(3);
            assertThat(ts.rows().get(0)).containsExactly("a1", "b1", "c1");
            assertThat(ts.rows().get(1)).containsExactly("a2", "b2", "c2");
            assertThat(ts.rows().get(2)).containsExactly("a3", "b3", "c3");
            assertThat(ts.location().pageStart()).isEqualTo(1);
            assertThat(ts.location().pageEnd()).isEqualTo(1);
        }

        @Test
        @DisplayName("a three-sheet workbook produces three TableSections in sheet order with matching pageStart")
        void multiSheetProducesPerSheetSections() throws Exception {
            var sheets = new LinkedHashMap<String, List<List<String>>>();
            sheets.put("Alpha", List.of(List.of("alpha-r0c0")));
            sheets.put("Beta", List.of(List.of("beta-r0c0")));
            sheets.put("Gamma", List.of(List.of("gamma-r0c0")));
            var xlsxPath = writeXlsx(tempDir, sheets);

            var doc = XlsxDocumentParser.parse(xlsxPath);

            assertThat(doc.metadata().pageCount()).isEqualTo(3);
            assertThat(doc.sections()).hasSize(3);

            for (int i = 0; i < 3; i++) {
                var section = doc.sections().get(i);
                assertThat(section).isInstanceOf(TableSection.class);
                var ts = (TableSection) section;
                assertThat(ts.location().pageStart()).isEqualTo(i + 1);
                assertThat(ts.location().pageEnd()).isEqualTo(i + 1);
            }

            assertThat(((TableSection) doc.sections().get(0)).rows().get(0)).contains("alpha-r0c0");
            assertThat(((TableSection) doc.sections().get(1)).rows().get(0)).contains("beta-r0c0");
            assertThat(((TableSection) doc.sections().get(2)).rows().get(0)).contains("gamma-r0c0");
        }

        @Test
        @DisplayName("lineEnd reflects the last non-blank row in the sheet")
        void lineEndReflectsLastNonBlankRow() throws Exception {
            var rows = List.of(List.of("r1c1"), List.of("r2c1"), List.of("r3c1"));
            var xlsxPath = writeXlsx(tempDir, mapOf("Sheet1", rows));

            var doc = XlsxDocumentParser.parse(xlsxPath);

            var ts = (TableSection) doc.sections().get(0);
            // 3 rows -> lineEnd == 3
            assertThat(ts.location().lineEnd()).isEqualTo(3);
            assertThat(ts.location().lineStart()).isEqualTo(1);
        }

        @Test
        @DisplayName("a sheet with zero rows is omitted from sections but still counted in pageCount")
        void emptySheetOmitted() throws Exception {
            var sheets = new LinkedHashMap<String, List<List<String>>>();
            sheets.put("HasContent", List.of(List.of("real")));
            sheets.put("Empty", List.of());
            sheets.put("AlsoHasContent", List.of(List.of("also-real")));
            var xlsxPath = writeXlsx(tempDir, sheets);

            var doc = XlsxDocumentParser.parse(xlsxPath);

            assertThat(doc.metadata().pageCount()).isEqualTo(3);
            assertThat(doc.sections()).hasSize(2);
            assertThat(((TableSection) doc.sections().get(0)).rows().get(0)).contains("real");
            assertThat(((TableSection) doc.sections().get(1)).rows().get(0)).contains("also-real");
            // First content sheet is sheet index 0 -> pageStart 1.
            assertThat(((TableSection) doc.sections().get(0)).location().pageStart())
                    .isEqualTo(1);
            // The third sheet was index 2 -> pageStart 3 (skipped sheet does not collapse anchors).
            assertThat(((TableSection) doc.sections().get(1)).location().pageStart())
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("docId is stable across two byte-identical files and differs across content")
        void docIdStableAndDifferentiating() throws Exception {
            var pathA = writeXlsx(tempDir, mapOf("S", List.of(List.of("stable"))));
            var pathB = tempDir.resolve("renamed-elsewhere.xlsx");
            Files.copy(pathA, pathB);
            var pathC = writeXlsx(tempDir, mapOf("S", List.of(List.of("different"))));

            var docA = XlsxDocumentParser.parse(pathA);
            var docB = XlsxDocumentParser.parse(pathB);
            var docC = XlsxDocumentParser.parse(pathC);

            assertThat(docA.docId()).isEqualTo(docB.docId());
            assertThat(docA.docId()).isNotEqualTo(docC.docId());
        }

        @Test
        @DisplayName("number and date cells render via DataFormatter rather than raw POI numerics")
        void dateAndNumberCellsRenderViaDataFormatter() throws Exception {
            var xlsxPath = tempDir.resolve("typed-cells-" + System.nanoTime() + ".xlsx");
            try (var wb = new XSSFWorkbook()) {
                CreationHelper helper = wb.getCreationHelper();
                CellStyle dateStyle = wb.createCellStyle();
                dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-MM-dd"));

                var sheet = wb.createSheet("Typed");
                var row = sheet.createRow(0);

                var dateCell = row.createCell(0);
                var sdf = new SimpleDateFormat("yyyy-MM-dd");
                dateCell.setCellValue(sdf.parse("2026-04-01"));
                dateCell.setCellStyle(dateStyle);

                row.createCell(1).setCellValue(1.5);
                row.createCell(2).setCellValue("text");

                try (var out = Files.newOutputStream(xlsxPath)) {
                    wb.write(out);
                }
            }

            var doc = XlsxDocumentParser.parse(xlsxPath);

            assertThat(doc.sections()).hasSize(1);
            var ts = (TableSection) doc.sections().get(0);
            assertThat(ts.rows()).hasSize(1);
            assertThat(ts.rows().get(0)).containsExactly("2026-04-01", "1.5", "text");
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("null xlsxPath throws NullPointerException with 'xlsxPath' in the message")
        void nullPath() {
            assertThatNullPointerException()
                    .isThrownBy(() -> XlsxDocumentParser.parse(null))
                    .withMessageContaining("xlsxPath");
        }

        @Test
        @DisplayName("[APP 11.1] docId does NOT echo the file path or filename — content-addressable, no PII leak")
        void docIdDoesNotLeakFilePath() throws Exception {
            var sensitiveDir = Files.createDirectory(tempDir.resolve("customer-99999-PII"));
            var xlsxPath = sensitiveDir.resolve("medical-claim-jane-doe.xlsx");
            try (var wb = new XSSFWorkbook()) {
                var sheet = wb.createSheet("Sheet1");
                sheet.createRow(0).createCell(0).setCellValue("harmless content");
                try (var out = Files.newOutputStream(xlsxPath)) {
                    wb.write(out);
                }
            }

            var doc = XlsxDocumentParser.parse(xlsxPath);

            assertThat(doc.docId())
                    .doesNotContain("customer-99999-PII")
                    .doesNotContain("medical-claim-jane-doe")
                    .doesNotContain(sensitiveDir.toString())
                    .doesNotContain(xlsxPath.toString());
            assertThat(doc.docId()).startsWith("sha256:").hasSize("sha256:".length() + 64);
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("a nonexistent file throws ParseException with XLSX_FILE_NOT_FOUND")
        void nonexistentFile() {
            var missing = tempDir.resolve("does-not-exist-" + System.nanoTime() + ".xlsx");

            assertThatThrownBy(() -> XlsxDocumentParser.parse(missing))
                    .isInstanceOf(ParseException.class)
                    .satisfies(e -> {
                        var pe = (ParseException) e;
                        assertThat(pe.errorCode()).isEqualTo("XLSX_FILE_NOT_FOUND");
                        assertThat(pe.sourceName()).contains("does-not-exist");
                        assertThat(pe.pageNumber()).isEmpty();
                    });
        }

        @Test
        @DisplayName("a non-XLSX file (plain text with .xlsx extension) throws ParseException with cause")
        void nonXlsxFile() throws IOException {
            var notXlsx = tempDir.resolve("not-actually-xlsx.xlsx");
            Files.writeString(notXlsx, "This is plain text, not an XLSX zip.");

            assertThatThrownBy(() -> XlsxDocumentParser.parse(notXlsx))
                    .isInstanceOf(ParseException.class)
                    .satisfies(e -> {
                        var pe = (ParseException) e;
                        assertThat(pe.errorCode()).isEqualTo("XLSX_PARSE_FAILED");
                        assertThat(pe.getCause()).isInstanceOf(IOException.class);
                    });
        }

        @Test
        @DisplayName("a directory passed where a file is expected throws ParseException with XLSX_FILE_NOT_FOUND")
        void directoryRejected() {
            assertThatThrownBy(() -> XlsxDocumentParser.parse(tempDir))
                    .isInstanceOf(ParseException.class)
                    .satisfies(e -> {
                        var pe = (ParseException) e;
                        assertThat(pe.errorCode()).isEqualTo("XLSX_FILE_NOT_FOUND");
                    });
        }
    }

    // --- helpers ---------------------------------------------------------

    private static Map<String, List<List<String>>> mapOf(String name, List<List<String>> rows) {
        var m = new LinkedHashMap<String, List<List<String>>>();
        m.put(name, rows);
        return m;
    }

    private static Path writeXlsx(Path dir, Map<String, List<List<String>>> sheets) throws IOException {
        var path = dir.resolve("doc-" + System.nanoTime() + ".xlsx");
        try (var wb = new XSSFWorkbook()) {
            sheets.forEach((sheetName, rows) -> {
                var sheet = wb.createSheet(sheetName);
                for (int r = 0; r < rows.size(); r++) {
                    var row = sheet.createRow(r);
                    var cells = rows.get(r);
                    for (int c = 0; c < cells.size(); c++) {
                        row.createCell(c).setCellValue(cells.get(c));
                    }
                }
            });
            try (var out = Files.newOutputStream(path)) {
                wb.write(out);
            }
        }
        return path;
    }
}
