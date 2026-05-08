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

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1 entry point: read a DOCX file from disk into a {@link ParsedDocument} with one
 * {@link TextSection} per non-blank paragraph and one {@link TableSection} per table. Backed
 * by Apache POI ({@link XWPFDocument}) — chosen per AGENTS.md §4 "Build, don't synthesize"
 * (POI is the canonical Java OOXML lib; hand-rolling a DOCX zip+XML parser would violate the
 * principle).
 *
 * <p>v0.1.0-alpha intentionally treats DOCX as a single logical page
 * ({@code metadata.pageCount == 1}, every section anchored to {@code pageStart == 1}). Word
 * page breaks are a render-time concept driven by the consuming reader's font + page-size
 * settings — POI does not expose post-pagination page numbers without a layout engine.
 * Section-break-aware multi-page tracking arrives Phase 3+ per the project roadmap
 *
 * <p>The parser is a stateless utility — it has no per-instance config in v0.1.0-alpha
 * (so the static method form is the right level of API surface, per Engineering Principles
 * §5 "elegance over cleverness").
 *
 * @since 0.1.0
 */
public final class DocxDocumentParser {

    private static final Logger LOG = LoggerFactory.getLogger(DocxDocumentParser.class);

    private DocxDocumentParser() {
        throw new AssertionError("no instances");
    }

    /**
     * Parse the DOCX at {@code docxPath} into a {@link ParsedDocument}.
     *
     * @throws NullPointerException if {@code docxPath} is null.
     * @throws ParseException       if the file is missing, is not a DOCX, or POI raises any
     *                              IO error while reading. Cause-chain preserves the
     *                              underlying {@link IOException}.
     */
    public static ParsedDocument parse(Path docxPath) throws ParseException {
        Objects.requireNonNull(docxPath, "docxPath");

        if (!Files.isRegularFile(docxPath)) {
            throw new ParseException(
                    "DOCX_FILE_NOT_FOUND",
                    "DOCX file not found or is not a regular file: " + docxPath,
                    docxPath.toString(),
                    OptionalInt.empty());
        }

        try (InputStream in = Files.newInputStream(docxPath);
                XWPFDocument docx = new XWPFDocument(in)) {
            List<ParsedSection> sections = extractSections(docx);

            // DOCX is logically continuous in v0.1.0-alpha — see class Javadoc.
            var metadata = new DocumentMetadata(docxPath.getFileName().toString(), 1, Optional.empty());
            // docId is the SHA-256 of the file's bytes — content-addressable, tamper-evident,
            // and contains no path / username PII (data-privacy regulations (GDPR Art. 5, CCPA, Privacy Acts)).
            String docId = "sha256:" + sha256Hex(docxPath);

            int paragraphs = (int)
                    sections.stream().filter(s -> s instanceof TextSection).count();
            int tables = (int)
                    sections.stream().filter(s -> s instanceof TableSection).count();
            int figures = (int)
                    sections.stream().filter(s -> s instanceof FigureSection).count();
            LOG.debug("parsed docx path={} paragraphs={} tables={} figures={}", docxPath, paragraphs, tables, figures);
            return new ParsedDocument(docId, sections, metadata);
        } catch (IOException | RuntimeException e) {
            // POI throws unchecked exceptions (e.g. POIXMLException, NotOfficeXmlFileException)
            // for malformed DOCX; wrap as IOException-caused ParseException so callers get a
            // single typed boundary (AGENTS.md §2 — public API throws checked ParseException).
            Throwable cause = e instanceof IOException ? e : new IOException(e);
            throw new ParseException(
                    "DOCX_PARSE_FAILED",
                    "failed to parse DOCX: " + e.getMessage(),
                    docxPath.toString(),
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

    private static List<ParsedSection> extractSections(XWPFDocument docx) {
        var paragraphs = docx.getParagraphs();
        var sections = new ArrayList<ParsedSection>(
                paragraphs.size() + docx.getTables().size());
        int ordinal = 0;
        for (XWPFParagraph p : paragraphs) {
            ordinal++;
            var section = paragraphToSection(p, ordinal);
            if (section != null) {
                sections.add(section);
            }
        }
        int tableOrdinal = ordinal;
        for (XWPFTable table : docx.getTables()) {
            tableOrdinal++;
            sections.add(tableToSection(table, tableOrdinal));
        }
        return sections;
    }

    private static TextSection paragraphToSection(XWPFParagraph p, int ordinal) {
        String text = p.getText();
        if (text == null || text.isBlank()) {
            LOG.debug("skipping blank paragraph ordinal={}", ordinal);
            return null;
        }
        int lineCount = Math.max(1, (int) text.lines().count());
        var loc = new SourceLocation(1, 1, ordinal, ordinal + lineCount - 1, 0);
        return new TextSection(text, loc);
    }

    private static TableSection tableToSection(XWPFTable table, int ordinal) {
        var rows = new ArrayList<List<String>>(table.getRows().size());
        for (XWPFTableRow row : table.getRows()) {
            var cells = new ArrayList<String>(row.getTableCells().size());
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText());
            }
            rows.add(cells);
        }
        var loc = new SourceLocation(1, 1, ordinal, ordinal, 0);
        return new TableSection(rows, loc);
    }
}
