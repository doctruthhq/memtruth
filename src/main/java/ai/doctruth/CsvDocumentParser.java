package ai.doctruth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
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

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.RuntimeJsonMappingException;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1 entry point: read a CSV file from disk into a {@link ParsedDocument} containing
 * exactly one {@link TableSection} that mirrors the CSV row-major. Backed by
 * {@code com.fasterxml.jackson.dataformat:jackson-dataformat-csv} — chosen per AGENTS.md §4
 * "Build, don't synthesize" and ADR 0007 (zero new transitive deps; reuses the Jackson
 * already on the classpath).
 *
 * <p>v0.1.0-alpha contract:
 *
 * <ul>
 *   <li><b>Single page.</b> The whole CSV becomes one {@code TableSection} with
 *       {@code pageStart == pageEnd == 1}; rows map to {@link SourceLocation} lines
 *       ({@code lineStart == 1}, {@code lineEnd == rows.size()}).
 *   <li><b>No header detection.</b> Every row is data — the caller decides whether row 0
 *       is a header. Header-aware parsing arrives in Phase 2.
 *   <li><b>Comma-only delimiter.</b> Auto-detection of {@code ;}, tab, and {@code |}
 *       (common in EU CSV exports) is Phase 2.
 *   <li><b>Empty file → zero sections.</b> A file with no rows produces a
 *       {@code ParsedDocument} carrying zero sections — matches the PDF blank-page rule
 *       (empty content is noise, not signal).
 *   <li><b>Encoding fallback.</b> Read as UTF-8 first; on {@link MalformedInputException}
 *       (invalid UTF-8 byte sequence) retry with ISO-8859-1 (Latin-1 — bytewise lossless
 *       for any 8-bit input). The library never rejects a CSV on encoding alone.
 * </ul>
 *
 * <p>The parser is a stateless utility — it has no per-instance config in v0.1.0-alpha
 * (so the static method form is the right level of API surface, per AGENTS.md "Engineering
 * Principles" §5). When per-call options arrive (Phase 2: header / delimiter / charset
 * overrides) this becomes an instance class with a builder.
 *
 * @since 0.1.0
 */
public final class CsvDocumentParser {

    private static final Logger LOG = LoggerFactory.getLogger(CsvDocumentParser.class);

    /**
     * Single CsvMapper shared across calls — Jackson's mappers are documented thread-safe
     * once configured, so amortising the construction cost is the idiomatic move. The
     * {@code WRAP_AS_ARRAY} feature switches the parser into untyped row mode, in which
     * each CSV record is exposed as a JSON array of strings — exactly the v0.1.0-alpha
     * contract: no fixed column set, no header, every row is data.
     */
    private static final CsvMapper CSV_MAPPER = new CsvMapper().enable(CsvParser.Feature.WRAP_AS_ARRAY);

    private CsvDocumentParser() {
        throw new AssertionError("no instances");
    }

    /**
     * Parse the CSV at {@code csvPath} into a {@link ParsedDocument}.
     *
     * @throws NullPointerException if {@code csvPath} is null.
     * @throws ParseException       if the file is missing, is not a regular file, or Jackson
     *                              raises any IO error while reading. Cause-chain preserves
     *                              the underlying {@link IOException}.
     */
    public static ParsedDocument parse(Path csvPath) throws ParseException {
        Objects.requireNonNull(csvPath, "csvPath");

        if (!Files.isRegularFile(csvPath)) {
            throw new ParseException(
                    "CSV_FILE_NOT_FOUND",
                    "CSV file not found or is not a regular file: " + csvPath,
                    csvPath.toString(),
                    OptionalInt.empty());
        }

        try {
            List<List<String>> rows = readAllRows(csvPath);
            List<ParsedSection> sections = rowsToSections(rows);

            var metadata = new DocumentMetadata(csvPath.getFileName().toString(), 1, Optional.empty());
            // docId is the SHA-256 of the file's bytes — content-addressable, tamper-evident,
            // and contains no path / username PII (data-privacy regulations (GDPR Art. 5, CCPA, Privacy Acts)).
            String docId = "sha256:" + sha256Hex(csvPath);

            LOG.debug("parsed csv path={} rows={} sections={}", csvPath, rows.size(), sections.size());
            return new ParsedDocument(docId, sections, metadata);
        } catch (IOException e) {
            throw parseFailed(csvPath, e);
        } catch (RuntimeJsonMappingException e) {
            throw parseFailed(csvPath, e);
        }
    }

    private static ParseException parseFailed(Path csvPath, Exception e) {
        return new ParseException(
                "CSV_PARSE_FAILED",
                "failed to parse CSV: " + e.getMessage(),
                csvPath.toString(),
                OptionalInt.empty(),
                e);
    }

    private static List<List<String>> readAllRows(Path csvPath) throws IOException {
        try {
            return readAllRowsWithCharset(csvPath, StandardCharsets.UTF_8);
        } catch (MalformedInputException utf8Failed) {
            // The CSV contains bytes that aren't valid UTF-8 — typical for legacy Latin-1
            // / Windows-1252 exports. Fall back to ISO-8859-1, which is bytewise lossless
            // for any 8-bit input. Documented behaviour per the class Javadoc.
            LOG.debug("csv utf-8 decode failed, falling back to iso-8859-1 path={}", csvPath);
            return readAllRowsWithCharset(csvPath, StandardCharsets.ISO_8859_1);
        }
    }

    private static List<List<String>> readAllRowsWithCharset(Path csvPath, Charset charset) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(csvPath, charset);
                MappingIterator<String[]> it =
                        CSV_MAPPER.readerFor(String[].class).readValues(reader)) {
            var rows = new ArrayList<List<String>>();
            while (it.hasNext()) {
                rows.add(List.of(it.next()));
            }
            return rows;
        }
    }

    private static List<ParsedSection> rowsToSections(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var loc = new SourceLocation(1, 1, 1, rows.size(), 0);
        return List.of(new TableSection(rows, loc));
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
}
