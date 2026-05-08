package ai.doctruth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1 entry point: read a PDF file from disk into a {@link ParsedDocument} with
 * source locations preserved per detected layout block. PDFBox owns raw glyph extraction;
 * {@link PdfPageBlockExtractor} owns page-level grouping and visual classification.
 *
 * @since 0.1.0
 */
public final class PdfDocumentParser {

    private static final Logger LOG = LoggerFactory.getLogger(PdfDocumentParser.class);

    private PdfDocumentParser() {
        throw new AssertionError("no instances");
    }

    /**
     * Parse the PDF at {@code pdfPath} into a {@link ParsedDocument}.
     *
     * @throws NullPointerException if {@code pdfPath} is null.
     * @throws ParseException       if the file is missing, is not a PDF, is encrypted with
     *                              an unknown password, or PDFBox raises any IO error.
     */
    public static ParsedDocument parse(Path pdfPath) throws ParseException {
        Objects.requireNonNull(pdfPath, "pdfPath");
        requireRegularFile(pdfPath);
        try (PDDocument pdf = Loader.loadPDF(pdfPath.toFile())) {
            int pageCount = pdf.getNumberOfPages();
            var metadata = new DocumentMetadata(pdfPath.getFileName().toString(), pageCount, Optional.empty());
            String docId = "sha256:" + sha256Hex(pdfPath);
            var sections = extractSections(pdf, pageCount);
            LOG.debug("parsed pdf path={} pages={} sections={}", pdfPath, pageCount, sections.size());
            return new ParsedDocument(docId, sections, metadata);
        } catch (IOException e) {
            throw new ParseException(
                    "PDF_PARSE_FAILED",
                    "failed to parse PDF: " + e.getMessage(),
                    pdfPath.toString(),
                    OptionalInt.empty(),
                    e);
        }
    }

    private static void requireRegularFile(Path pdfPath) throws ParseException {
        if (!Files.isRegularFile(pdfPath)) {
            throw new ParseException(
                    "PDF_FILE_NOT_FOUND",
                    "PDF file not found or is not a regular file: " + pdfPath,
                    pdfPath.toString(),
                    OptionalInt.empty());
        }
    }

    private static String sha256Hex(Path path) throws IOException {
        MessageDigest md = sha256();
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be supported by every JDK", e);
        }
    }

    private static List<ParsedSection> extractSections(PDDocument pdf, int pageCount) throws IOException {
        var sections = new ArrayList<ParsedSection>(pageCount);
        for (int page = 1; page <= pageCount; page++) {
            appendPageSections(pdf, page, sections);
        }
        return sections;
    }

    private static void appendPageSections(PDDocument pdf, int page, List<ParsedSection> sections) throws IOException {
        var blocks = PdfPageBlockExtractor.detectBlocksOnPage(pdf, page);
        if (blocks.isEmpty()) {
            LOG.debug("skipping blank page page={}", page);
            return;
        }
        var counts = new EnumMap<BlockKind, Integer>(BlockKind.class);
        for (var block : blocks) {
            sections.add(new TextSection(block.text(), block.location(), block.kind()));
            counts.merge(block.kind(), 1, Integer::sum);
        }
        LOG.debug("page={} blocks={} kinds={}", page, blocks.size(), counts);
    }

    static BlockKind classify(String blockText, double avgCharHeight, double pageMedianHeight) {
        return PdfPageBlockExtractor.classify(blockText, avgCharHeight, pageMedianHeight);
    }
}
