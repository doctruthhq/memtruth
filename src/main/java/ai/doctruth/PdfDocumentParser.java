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

import ai.doctruth.spi.OcrEngine;
import ai.doctruth.spi.OcrPageResult;
import ai.doctruth.spi.OcrRegion;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
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
    private static final int LOW_TEXT_LAYER_CHARS = 50;
    private static final float OCR_RENDER_DPI = 150f;

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
        return parse(pdfPath, OcrEngine.NOOP);
    }

    /**
     * Parse a PDF with an OCR engine wired into the page runtime. Each page is preflighted
     * before DocTruth block assembly; pages with an insufficient text layer are rendered and
     * routed through {@code ocrEngine}, while normal text-layer pages stay on the PDFBox block
     * path.
     */
    public static ParsedDocument parse(Path pdfPath, OcrEngine ocrEngine) throws ParseException {
        Objects.requireNonNull(pdfPath, "pdfPath");
        Objects.requireNonNull(ocrEngine, "ocrEngine");
        requireRegularFile(pdfPath);
        try (PDDocument pdf = Loader.loadPDF(pdfPath.toFile())) {
            int pageCount = pdf.getNumberOfPages();
            var metadata = new DocumentMetadata(pdfPath.getFileName().toString(), pageCount, Optional.empty());
            String docId = "sha256:" + sha256Hex(pdfPath);
            var sections = extractSections(pdf, pageCount, ocrEngine);
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

    private static List<ParsedSection> extractSections(PDDocument pdf, int pageCount, OcrEngine ocrEngine) throws IOException {
        var sections = new ArrayList<ParsedSection>(pageCount);
        for (int page = 1; page <= pageCount; page++) {
            if (shouldRouteToOcr(pdf, page, ocrEngine)) {
                appendOcrPageSections(pdf, page, ocrEngine, sections);
            } else {
                appendPageSections(pdf, page, sections);
            }
        }
        return mergeTableContinuations(sections);
    }

    private static List<ParsedSection> mergeTableContinuations(List<ParsedSection> sections) {
        var merged = new ArrayList<ParsedSection>(sections.size());
        for (var section : sections) {
            if (section instanceof TableSection current
                    && !merged.isEmpty()
                    && merged.getLast() instanceof TableSection previous
                    && isTableContinuation(previous, current)) {
                merged.set(merged.size() - 1, mergeTables(previous, current));
            } else {
                merged.add(section);
            }
        }
        return List.copyOf(merged);
    }

    private static boolean isTableContinuation(TableSection previous, TableSection current) {
        return previous.location().pageEnd() + 1 == current.location().pageStart()
                && !previous.rows().isEmpty()
                && !current.rows().isEmpty()
                && previous.rows().getFirst().size() == current.rows().getFirst().size()
                && normalizedRow(previous.rows().getFirst()).equals(normalizedRow(current.rows().getFirst()))
                && alignedTableBoxes(previous, current);
    }

    private static TableSection mergeTables(TableSection previous, TableSection current) {
        var rows = new ArrayList<List<String>>();
        rows.addAll(previous.rows());
        rows.addAll(current.rows().subList(1, current.rows().size()));

        int rowOffset = previous.rows().size() - 1;
        var regions = new ArrayList<TableCellRegion>();
        regions.addAll(previous.cellRegions());
        for (var region : current.cellRegions()) {
            if (region.row() == 0) {
                continue;
            }
            regions.add(new TableCellRegion(
                    region.page(),
                    region.row() + rowOffset,
                    region.column(),
                    region.rowEnd() + rowOffset,
                    region.columnEnd(),
                    region.boundingBox()));
        }

        var location = new SourceLocation(
                previous.location().pageStart(),
                current.location().pageEnd(),
                previous.location().lineStart(),
                current.location().lineEnd(),
                previous.location().charOffset());
        return new TableSection(rows, location, previous.boundingBox().or(current::boundingBox), regions);
    }

    private static String normalizedRow(List<String> row) {
        return row.stream()
                .map(value -> value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT))
                .toList()
                .toString();
    }

    private static boolean alignedTableBoxes(TableSection previous, TableSection current) {
        if (previous.boundingBox().isEmpty() || current.boundingBox().isEmpty()) {
            return true;
        }
        var left = previous.boundingBox().get();
        var right = current.boundingBox().get();
        return Math.abs(left.x0() - right.x0()) <= 20.0
                && Math.abs(left.x1() - right.x1()) <= 20.0;
    }

    private static boolean shouldRouteToOcr(PDDocument pdf, int page, OcrEngine ocrEngine) throws IOException {
        return ocrEngine != OcrEngine.NOOP && textLayerCharCount(pdf, page) < LOW_TEXT_LAYER_CHARS;
    }

    private static int textLayerCharCount(PDDocument pdf, int page) throws IOException {
        var count = new int[1];
        var stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                count[0] += text.replaceAll("\\s+", "").length();
            }
        };
        stripper.setSortByPosition(true);
        stripper.setSuppressDuplicateOverlappingText(true);
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        stripper.getText(pdf);
        return count[0];
    }

    private static void appendOcrPageSections(
            PDDocument pdf, int page, OcrEngine ocrEngine, List<ParsedSection> sections) throws IOException {
        var image = new PDFRenderer(pdf).renderImageWithDPI(page - 1, OCR_RENDER_DPI);
        OcrPageResult result = Objects.requireNonNull(ocrEngine.ocr(image, page), "ocr result");
        if (result.text().isBlank()) {
            LOG.debug("skipping blank OCR page page={}", page);
            return;
        }
        int lineCount = Math.max(1, (int) result.text().lines().count());
        sections.add(new TextSection(
                result.text().stripTrailing(),
                new SourceLocation(page, page, 1, lineCount, 0),
                BlockKind.BODY,
                ocrBoundingBox(result, image.getWidth(), image.getHeight())));
        LOG.debug("page={} routed=ocr chars={} confidence={}", page, result.text().length(), result.confidence());
    }

    private static Optional<BoundingBox> ocrBoundingBox(OcrPageResult result, int imageWidth, int imageHeight) {
        if (result.regions().isEmpty() || imageWidth <= 0 || imageHeight <= 0) {
            return Optional.of(new BoundingBox(0.0, 0.0, 1000.0, 1000.0));
        }
        int x0 = result.regions().stream().mapToInt(OcrRegion::x).min().orElse(0);
        int y0 = result.regions().stream().mapToInt(OcrRegion::y).min().orElse(0);
        int x1 = result.regions().stream().mapToInt(region -> region.x() + region.width()).max().orElse(imageWidth);
        int y1 = result.regions().stream().mapToInt(region -> region.y() + region.height()).max().orElse(imageHeight);
        return Optional.of(new BoundingBox(
                clamp1000(x0 * 1000.0 / imageWidth),
                clamp1000(y0 * 1000.0 / imageHeight),
                clamp1000(x1 * 1000.0 / imageWidth),
                clamp1000(y1 * 1000.0 / imageHeight)));
    }

    private static double clamp1000(double value) {
        return Math.max(0.0, Math.min(1000.0, value));
    }

    private static void appendPageSections(PDDocument pdf, int page, List<ParsedSection> sections) throws IOException {
        var blocks = PdfPageBlockExtractor.detectBlocksOnPage(pdf, page);
        if (blocks.isEmpty()) {
            LOG.debug("skipping blank page page={}", page);
            return;
        }
        var counts = new EnumMap<BlockKind, Integer>(BlockKind.class);
        var tables = PdfPageTableExtractor.detectTableBlocksOnPage(pdf, page);
        var pendingTables = new ArrayList<>(tables.stream()
                .sorted(PdfDocumentParser::compareTableBlocks)
                .toList());
        for (var block : blocks) {
            if (insideAnyTable(block, tables)) {
                continue;
            }
            appendTablesBeforeBlock(sections, pendingTables, block);
            var caption = PdfCaptionBinder.bindCaption(block, tables);
            if (caption.isPresent()) {
                sections.add(caption.get());
            } else {
                sections.add(new TextSection(block.text(), block.location(), block.kind(), block.boundingBox()));
                counts.merge(block.kind(), 1, Integer::sum);
            }
        }
        pendingTables.stream().map(PdfPageTableExtractor.TableBlock::section).forEach(sections::add);
        LOG.debug("page={} blocks={} tables={} kinds={}", page, blocks.size(), tables.size(), counts);
    }

    private static void appendTablesBeforeBlock(
            List<ParsedSection> sections, List<PdfPageTableExtractor.TableBlock> pendingTables, PdfTextBlock block) {
        if (block.boundingBox().isEmpty()) {
            return;
        }
        var iterator = pendingTables.iterator();
        while (iterator.hasNext()) {
            var table = iterator.next();
            if (isBeforeOrSameReadingPosition(table.boundingBox(), block.boundingBox().get())) {
                sections.add(table.section());
                iterator.remove();
            }
        }
    }

    private static boolean insideAnyTable(PdfTextBlock block, List<PdfPageTableExtractor.TableBlock> tables) {
        return tables.stream().anyMatch(table -> table.contains(block));
    }

    private static int compareTableBlocks(PdfPageTableExtractor.TableBlock left, PdfPageTableExtractor.TableBlock right) {
        int y = Double.compare(left.boundingBox().y0(), right.boundingBox().y0());
        return y != 0 ? y : Double.compare(left.boundingBox().x0(), right.boundingBox().x0());
    }

    private static boolean isBeforeOrSameReadingPosition(BoundingBox table, BoundingBox block) {
        if (table.y0() < block.y0() - 1.0) {
            return true;
        }
        return Math.abs(table.y0() - block.y0()) <= 1.0 && table.x0() <= block.x0();
    }

    static BlockKind classify(String blockText, double avgCharHeight, double pageMedianHeight) {
        return PdfPageBlockExtractor.classify(blockText, avgCharHeight, pageMedianHeight);
    }
}
