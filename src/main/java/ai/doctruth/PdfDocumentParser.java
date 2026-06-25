package ai.doctruth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import ai.doctruth.spi.OcrEngine;
import ai.doctruth.spi.OcrPageResult;
import ai.doctruth.spi.OcrRegion;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
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
    private static final Pattern PAGE_NUMBER_FURNITURE = Pattern.compile(
            "(?i)^(?:page\\s+)?\\d+\\s*(?:/|of)\\s*\\d+$|^page\\s+\\d+$");
    private static final Pattern LEGAL_OR_CONFIDENTIAL_FURNITURE = Pattern.compile(
            "(?i).*(confidential|proprietary|copyright|all rights reserved|draft|internal use).*");
    private static final Pattern STANDALONE_BODY_FIELD =
            Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} /&().-]{1,40}:\\s+\\S.+$");
    private static final double PARAGRAPH_VERTICAL_GAP = 32.0;
    private static final double PARAGRAPH_LEFT_TOLERANCE = 24.0;
    private static final double PARAGRAPH_MIN_HORIZONTAL_OVERLAP = 0.50;

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
            var extracted = extractSections(pdf, pageCount, ocrEngine);
            var document = new ParsedDocument(docId, extracted.sections(), metadata);
            ParsedDocumentArtifacts.attachDiscardedBlocks(document, extracted.discardedBlocks());
            LOG.debug("parsed pdf path={} pages={} sections={}", pdfPath, pageCount, extracted.sections().size());
            return document;
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

    private static ExtractedSections extractSections(PDDocument pdf, int pageCount, OcrEngine ocrEngine) throws IOException {
        var sections = new ArrayList<ParsedSection>(pageCount);
        var discarded = new ArrayList<DiscardedBlock>();
        var pages = preflightTextPages(pdf, pageCount, ocrEngine);
        var furniture = repeatedFurnitureKeys(pages);
        for (int page = 1; page <= pageCount; page++) {
            var pageBlocks = pages.get(page);
            if (pageBlocks.routeToOcr()) {
                appendOcrPageSections(pdf, page, ocrEngine, sections);
            } else {
                appendPageSections(pdf, page, pageBlocks, furniture, sections, discarded);
            }
        }
        return new ExtractedSections(mergeTableContinuations(sections), List.copyOf(discarded));
    }

    private static Map<Integer, PageBlocks> preflightTextPages(PDDocument pdf, int pageCount, OcrEngine ocrEngine)
            throws IOException {
        var out = new HashMap<Integer, PageBlocks>();
        for (int page = 1; page <= pageCount; page++) {
            var blocks = PdfPageBlockExtractor.detectBlocksOnPage(pdf, page);
            boolean routeToOcr = shouldRouteToOcr(blocks, ocrEngine);
            out.put(page, new PageBlocks(page, routeToOcr, blocks));
        }
        return out;
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

    private static boolean shouldRouteToOcr(List<PdfTextBlock> blocks, OcrEngine ocrEngine) {
        return ocrEngine != OcrEngine.NOOP && textLayerCharCount(blocks) < LOW_TEXT_LAYER_CHARS;
    }

    private static int textLayerCharCount(List<PdfTextBlock> blocks) {
        return blocks.stream()
                .map(PdfTextBlock::text)
                .mapToInt(text -> text.replaceAll("\\s+", "").length())
                .sum();
    }

    private static void appendOcrPageSections(
            PDDocument pdf, int page, OcrEngine ocrEngine, List<ParsedSection> sections) throws IOException {
        var image = new PDFRenderer(pdf).renderImageWithDPI(page - 1, OCR_RENDER_DPI);
        OcrPageResult result = Objects.requireNonNull(ocrEngine.ocr(image, page), "ocr result");
        if (result.text().isBlank()) {
            LOG.debug("skipping blank OCR page page={}", page);
            return;
        }
        if (appendOcrRegionSections(result, page, image.getWidth(), image.getHeight(), sections)) {
            LOG.debug("page={} routed=ocr regions={} confidence={}", page, result.regions().size(), result.confidence());
            return;
        }
        appendAggregateOcrSection(result, page, image.getWidth(), image.getHeight(), sections);
        LOG.debug("page={} routed=ocr chars={} confidence={}", page, result.text().length(), result.confidence());
    }

    private static boolean appendOcrRegionSections(
            OcrPageResult result, int page, int imageWidth, int imageHeight, List<ParsedSection> sections) {
        if (result.regions().isEmpty()) {
            return false;
        }
        int firstSize = sections.size();
        int nextLine = 1;
        for (var region : result.regions()) {
            String text = region.text().strip();
            if (text.isBlank()) {
                continue;
            }
            int lineCount = Math.max(1, (int) text.lines().count());
            sections.add(new TextSection(
                    text,
                    new SourceLocation(page, page, nextLine, nextLine + lineCount - 1, 0),
                    BlockKind.BODY,
                    ocrRegionBoundingBox(region, imageWidth, imageHeight)));
            nextLine += lineCount;
        }
        return sections.size() > firstSize;
    }

    private static void appendAggregateOcrSection(
            OcrPageResult result, int page, int imageWidth, int imageHeight, List<ParsedSection> sections) {
        int lineCount = Math.max(1, (int) result.text().lines().count());
        sections.add(new TextSection(
                result.text().stripTrailing(),
                new SourceLocation(page, page, 1, lineCount, 0),
                BlockKind.BODY,
                ocrBoundingBox(result, imageWidth, imageHeight)));
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

    private static Optional<BoundingBox> ocrRegionBoundingBox(OcrRegion region, int imageWidth, int imageHeight) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            return Optional.of(new BoundingBox(0.0, 0.0, 1000.0, 1000.0));
        }
        return Optional.of(new BoundingBox(
                clamp1000(region.x() * 1000.0 / imageWidth),
                clamp1000(region.y() * 1000.0 / imageHeight),
                clamp1000((region.x() + region.width()) * 1000.0 / imageWidth),
                clamp1000((region.y() + region.height()) * 1000.0 / imageHeight)));
    }

    private static double clamp1000(double value) {
        return Math.max(0.0, Math.min(1000.0, value));
    }

    private static void appendPageSections(
            PDDocument pdf,
            int page,
            PageBlocks pageBlocks,
            Set<FurnitureKey> furniture,
            List<ParsedSection> sections,
            List<DiscardedBlock> discarded) throws IOException {
        var blocks = pageBlocks.blocks();
        if (blocks.isEmpty()) {
            LOG.debug("skipping blank page page={}", page);
            return;
        }
        var counts = new EnumMap<BlockKind, Integer>(BlockKind.class);
        var tables = PdfPageTableExtractor.detectTableBlocksOnPage(pdf, page);
        var pendingTables = new ArrayList<>(tables.stream()
                .sorted(PdfDocumentParser::compareTableBlocks)
                .toList());
        var pendingParagraph = new ArrayList<PdfTextBlock>();
        for (var block : blocks) {
            if (insideAnyTable(block, tables)) {
                continue;
            }
            if (hasTablesBeforeBlock(pendingTables, block)) {
                flushParagraph(sections, pendingParagraph);
                appendTablesBeforeBlock(sections, pendingTables, block);
            }
            var furnitureKey = furnitureKey(block);
            if (furnitureKey.isPresent() && furniture.contains(furnitureKey.get())) {
                flushParagraph(sections, pendingParagraph);
                discarded.add(new DiscardedBlock(
                        page,
                        furnitureKey.get().reason(),
                        block.text(),
                        block.boundingBox()));
                continue;
            }
            var caption = PdfCaptionBinder.bindCaption(block, tables);
            if (caption.isPresent()) {
                flushParagraph(sections, pendingParagraph);
                sections.add(caption.get());
            } else if (block.kind() == BlockKind.BODY && canAppendParagraph(pendingParagraph, block)) {
                pendingParagraph.add(block);
            } else {
                flushParagraph(sections, pendingParagraph);
                if (block.kind() == BlockKind.BODY) {
                    pendingParagraph.add(block);
                } else {
                    sections.add(new TextSection(block.text(), block.location(), block.kind(), block.boundingBox()));
                }
                counts.merge(block.kind(), 1, Integer::sum);
            }
        }
        flushParagraph(sections, pendingParagraph);
        pendingTables.stream().map(PdfPageTableExtractor.TableBlock::section).forEach(sections::add);
        LOG.debug("page={} blocks={} tables={} kinds={}", page, blocks.size(), tables.size(), counts);
    }

    private static boolean canAppendParagraph(List<PdfTextBlock> pendingParagraph, PdfTextBlock block) {
        return pendingParagraph.isEmpty() || sameWrappedParagraph(pendingParagraph.getLast(), block);
    }

    private static void flushParagraph(List<ParsedSection> sections, List<PdfTextBlock> pendingParagraph) {
        if (pendingParagraph.isEmpty()) {
            return;
        }
        sections.add(mergedParagraph(pendingParagraph));
        pendingParagraph.clear();
    }

    private static TextSection mergedParagraph(List<PdfTextBlock> blocks) {
        if (blocks.size() == 1) {
            var block = blocks.getFirst();
            return new TextSection(paragraphText(block.text()), block.location(), block.kind(), block.boundingBox());
        }
        var first = blocks.getFirst();
        var last = blocks.getLast();
        var location = new SourceLocation(
                first.location().pageStart(),
                last.location().pageEnd(),
                first.location().lineStart(),
                Math.max(first.location().lineEnd(), last.location().lineEnd()),
                first.location().charOffset());
        return new TextSection(
                blocks.stream().map(PdfTextBlock::text).map(PdfDocumentParser::paragraphText).collect(Collectors.joining(" ")),
                location,
                BlockKind.BODY,
                paragraphBox(blocks));
    }

    private static String paragraphText(String text) {
        return text.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining(" "));
    }

    private static Optional<BoundingBox> paragraphBox(List<PdfTextBlock> blocks) {
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (var block : blocks) {
            if (block.boundingBox().isEmpty()) {
                continue;
            }
            var box = block.boundingBox().orElseThrow();
            x0 = Math.min(x0, box.x0());
            y0 = Math.min(y0, box.y0());
            x1 = Math.max(x1, box.x1());
            y1 = Math.max(y1, box.y1());
            found = true;
        }
        return found ? Optional.of(new BoundingBox(x0, y0, x1, y1)) : Optional.empty();
    }

    private static boolean sameWrappedParagraph(PdfTextBlock previous, PdfTextBlock current) {
        if (previous.boundingBox().isEmpty() || current.boundingBox().isEmpty() || !samePage(previous, current)) {
            return false;
        }
        var a = previous.boundingBox().orElseThrow();
        var b = current.boundingBox().orElseThrow();
        double verticalGap = b.y0() - a.y1();
        return verticalGap >= 0.0
                && verticalGap <= PARAGRAPH_VERTICAL_GAP
                && alignedParagraphLines(a, b)
                && !looksLikeStandaloneBodyField(previous.text())
                && !looksLikeStandaloneBodyField(current.text());
    }

    private static boolean alignedParagraphLines(BoundingBox previous, BoundingBox current) {
        if (Math.abs(previous.x0() - current.x0()) <= PARAGRAPH_LEFT_TOLERANCE) {
            return true;
        }
        double overlap = Math.max(0.0, Math.min(previous.x1(), current.x1()) - Math.max(previous.x0(), current.x0()));
        double minWidth = Math.max(1.0, Math.min(previous.x1() - previous.x0(), current.x1() - current.x0()));
        return overlap / minWidth >= PARAGRAPH_MIN_HORIZONTAL_OVERLAP;
    }

    private static boolean looksLikeStandaloneBodyField(String text) {
        String trimmed = text.strip();
        return !trimmed.contains("\n") && STANDALONE_BODY_FIELD.matcher(trimmed).matches();
    }

    private static boolean samePage(PdfTextBlock previous, PdfTextBlock current) {
        return previous.location().pageStart() == current.location().pageStart()
                && previous.location().pageEnd() == current.location().pageEnd();
    }

    private static Set<FurnitureKey> repeatedFurnitureKeys(Map<Integer, PageBlocks> pages) {
        if (pages.size() < 2) {
            return Set.of();
        }
        var counts = new HashMap<FurnitureKey, Set<Integer>>();
        for (var page : pages.values()) {
            for (var block : page.blocks()) {
                furnitureKey(block).ifPresent(key -> counts.computeIfAbsent(key, ignored -> new HashSet<>())
                        .add(page.page()));
            }
        }
        var repeated = new HashSet<FurnitureKey>();
        counts.forEach((key, pageSet) -> {
            if (pageSet.size() >= 2) {
                repeated.add(key);
            }
        });
        return Set.copyOf(repeated);
    }

    private static Optional<FurnitureKey> furnitureKey(PdfTextBlock block) {
        if (block.boundingBox().isEmpty()) {
            return Optional.empty();
        }
        var box = block.boundingBox().get();
        String reason = furnitureReason(box).orElse(null);
        if (reason == null) {
            return Optional.empty();
        }
        String text = normalizeFurnitureText(block.text());
        if (text.isBlank() || text.length() > 120) {
            return Optional.empty();
        }
        if (PAGE_NUMBER_FURNITURE.matcher(text).matches()) {
            return Optional.of(new FurnitureKey(reason, normalizePageNumberFurniture(text)));
        }
        if (LEGAL_OR_CONFIDENTIAL_FURNITURE.matcher(text).matches()) {
            return Optional.of(new FurnitureKey(reason, text));
        }
        return Optional.empty();
    }

    private static Optional<String> furnitureReason(BoundingBox box) {
        if (box.y0() <= 100.0) {
            return Optional.of("repeated_header");
        }
        if (box.y1() >= 900.0) {
            return Optional.of("repeated_footer");
        }
        return Optional.empty();
    }

    private static String normalizeFurnitureText(String text) {
        return text.strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizePageNumberFurniture(String text) {
        return text.replaceAll("\\d+", "#");
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

    private static boolean hasTablesBeforeBlock(
            List<PdfPageTableExtractor.TableBlock> pendingTables, PdfTextBlock block) {
        return block.boundingBox().isPresent()
                && pendingTables.stream()
                        .anyMatch(table -> isBeforeOrSameReadingPosition(table.boundingBox(), block.boundingBox().get()));
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

    private record ExtractedSections(List<ParsedSection> sections, List<DiscardedBlock> discardedBlocks) {}

    private record PageBlocks(int page, boolean routeToOcr, List<PdfTextBlock> blocks) {}

    private record FurnitureKey(String reason, String normalizedText) {}
}
