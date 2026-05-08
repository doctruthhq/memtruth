package ai.doctruth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1 entry point: read an XLSX file from disk into a {@link ParsedDocument} with one
 * {@link TableSection} per non-empty sheet. Backed by Apache POI ({@link XSSFWorkbook}) —
 * chosen per AGENTS.md §4 "Build, don't synthesize" (POI is the canonical Java OOXML lib).
 *
 * <p>v0.1.0-alpha sheet-as-page analogy: spreadsheet workbooks have no native "pages" the way
 * PDFs do, but every sheet is a self-contained tabular surface. We map each sheet to a logical
 * page and each row to a logical line so that {@link SourceLocation} stays consistent across
 * formats — a {@link Citation} pointing at "page 2 line 5" of an XLSX document means
 * "sheet index 1 (0-indexed) row index 4". Sheet name is intentionally not part of the
 * location record (would force a 6th component); downstream consumers can fetch it from
 * {@link DocumentMetadata} extensions in a later phase.
 *
 * <p>Cell rendering uses POI's {@link DataFormatter#formatCellValue(Cell)} so dates,
 * percentages, and formula-cached values appear as the user sees them in Excel — not as raw
 * doubles. Empty/null cells render as the empty string {@code ""}; trailing all-blank rows
 * are trimmed from each sheet, but interior all-blank rows are preserved (they convey layout).
 *
 * <p>The parser is a stateless utility — it has no per-instance config in v0.1.0-alpha
 * (so the static method form is the right level of API surface, per Engineering Principles
 * §5 "elegance over cleverness").
 *
 * @since 0.1.0
 */
public final class XlsxDocumentParser {

    private static final Logger LOG = LoggerFactory.getLogger(XlsxDocumentParser.class);

    private XlsxDocumentParser() {
        throw new AssertionError("no instances");
    }

    /**
     * Parse the XLSX at {@code xlsxPath} into a {@link ParsedDocument}.
     *
     * @throws NullPointerException if {@code xlsxPath} is null.
     * @throws ParseException       if the file is missing, is not an XLSX (e.g. legacy
     *                              {@code .xls} binary, plain text, PDF mis-renamed), or POI
     *                              raises any IO error while reading. Cause-chain preserves
     *                              the underlying {@link IOException}.
     */
    public static ParsedDocument parse(Path xlsxPath) throws ParseException {
        Objects.requireNonNull(xlsxPath, "xlsxPath");

        if (!Files.isRegularFile(xlsxPath)) {
            throw new ParseException(
                    "XLSX_FILE_NOT_FOUND",
                    "XLSX file not found or is not a regular file: " + xlsxPath,
                    xlsxPath.toString(),
                    OptionalInt.empty());
        }

        try (InputStream in = Files.newInputStream(xlsxPath);
                XSSFWorkbook wb = new XSSFWorkbook(in)) {
            int sheetCount = wb.getNumberOfSheets();
            List<ParsedSection> sections = extractSections(wb);

            // pageCount must be >= 1 per DocumentMetadata invariant. A zero-sheet workbook is
            // not constructible with the POI API used here, but guard defensively.
            int pageCount = Math.max(1, sheetCount);
            var metadata = new DocumentMetadata(xlsxPath.getFileName().toString(), pageCount, Optional.empty());
            // docId is the SHA-256 of the file's bytes — content-addressable, tamper-evident,
            // and contains no path / username PII (data-privacy regulations (GDPR Art. 5, CCPA, Privacy Acts)).
            String docId = "sha256:" + sha256Hex(xlsxPath);

            LOG.debug("parsed xlsx path={} sheets={} sections={}", xlsxPath, sheetCount, sections.size());
            return new ParsedDocument(docId, sections, metadata);
        } catch (IOException | RuntimeException e) {
            // POI throws unchecked exceptions (e.g. POIXMLException, NotOfficeXmlFileException)
            // for malformed XLSX; wrap as IOException-caused ParseException so callers get a
            // single typed boundary (AGENTS.md §2 — public API throws checked ParseException).
            Throwable cause = e instanceof IOException ? e : new IOException(e);
            throw new ParseException(
                    "XLSX_PARSE_FAILED",
                    "failed to parse XLSX: " + e.getMessage(),
                    xlsxPath.toString(),
                    OptionalInt.empty(),
                    cause);
        }
    }

    private static String sha256Hex(Path path) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be supported by every JDK", e);
        }
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static List<ParsedSection> extractSections(XSSFWorkbook wb) {
        var formatter = new DataFormatter();
        int sheetCount = wb.getNumberOfSheets();
        var sections = new ArrayList<ParsedSection>(sheetCount);
        for (int i = 0; i < sheetCount; i++) {
            Sheet sheet = wb.getSheetAt(i);
            var section = sheetToSection(sheet, i, formatter);
            if (section == null) {
                LOG.debug("skipping empty sheet index={} name={}", i, sheet.getSheetName());
                continue;
            }
            sections.add(section);
        }
        return sections;
    }

    private static TableSection sheetToSection(Sheet sheet, int sheetIndex, DataFormatter formatter) {
        var rows = renderRows(sheet, formatter);
        if (rows.isEmpty()) {
            return null;
        }
        int pageNumber = sheetIndex + 1;
        var loc = new SourceLocation(pageNumber, pageNumber, 1, rows.size(), 0);
        return new TableSection(rows, loc);
    }

    private static List<List<String>> renderRows(Sheet sheet, DataFormatter formatter) {
        // POI's getLastRowNum() is -1 for an empty sheet and 0-indexed otherwise.
        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < 0) {
            return List.of();
        }
        var rendered = new ArrayList<List<String>>(lastRowNum + 1);
        for (int r = 0; r <= lastRowNum; r++) {
            rendered.add(renderRow(sheet.getRow(r), formatter));
        }
        // Trim trailing all-blank rows so lineEnd reflects the last meaningful row. Interior
        // all-blank rows stay so callers see real layout.
        int trimmed = rendered.size();
        while (trimmed > 0 && isAllBlank(rendered.get(trimmed - 1))) {
            trimmed--;
        }
        return rendered.subList(0, trimmed);
    }

    private static List<String> renderRow(Row row, DataFormatter formatter) {
        if (row == null) {
            return List.of();
        }
        // getLastCellNum() returns one past the last column; -1 for empty.
        short lastCellNum = row.getLastCellNum();
        if (lastCellNum <= 0) {
            return List.of();
        }
        var cells = new ArrayList<String>(lastCellNum);
        for (int c = 0; c < lastCellNum; c++) {
            Cell cell = row.getCell(c);
            cells.add(cell == null ? "" : formatter.formatCellValue(cell));
        }
        return cells;
    }

    private static boolean isAllBlank(List<String> row) {
        for (String cell : row) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
