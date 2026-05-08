// SPDX-License-Identifier: Apache-2.0
// doctruth-java evidence-overlay — visual proof of the audit chain.
// Takes a real PDF + an ExtractionResult and produces one PNG per cited page
// with every Citation drawn as a coloured highlight box on the rendered page.
// This is the eyeball companion to result.toAuditJson() — open the PNG and
// SEE which span of the source document justifies each extracted field.
package ai.doctruth.examples.evidenceoverlay;

import ai.doctruth.BlockKind;
import ai.doctruth.Citation;
import ai.doctruth.Confidence;
import ai.doctruth.ExtractionResult;
import ai.doctruth.ParsedDocument;
import ai.doctruth.PdfDocumentParser;
import ai.doctruth.Provenance;
import ai.doctruth.SourceLocation;
import ai.doctruth.TextSection;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Visual evidence overlay renderer. See class-level comment for motivation. */
public final class EvidenceOverlay {

    private static final Logger LOG = LoggerFactory.getLogger(EvidenceOverlay.class);

    /** Render at 150 DPI; PDF user space is 72 DPI, so the scale to image pixels is 150/72. */
    private static final float DPI = 150f;
    private static final float SCALE = DPI / 72f;

    /** Demo record — what an LlmProvider would normally fill from the PDF. Field names are
     *  the human-readable identifiers an auditor sees in the overlay legend. */
    record ResumeFields(String fullName, String email, String sectionHeader) {}

    /** Width of the sidebar (px) drawn to the right of the page where the legend lives. */
    private static final int SIDEBAR_WIDTH = 320;

    private EvidenceOverlay() {
        throw new AssertionError("no instances");
    }

    /**
     * Render every Citation in {@code result} as a coloured highlight box overlaid on the
     * matching page of {@code pdfPath}. One PNG per page that has at least one citation.
     */
    public static List<Path> render(Path pdfPath, ExtractionResult<?> result, Path outputDir)
            throws Exception {
        Files.createDirectories(outputDir);
        // Group citations by pageStart — v0.1 treats pageStart as the canonical page.
        // Multi-page highlights are deferred to Phase 3; logged so it's auditable, not silent.
        Map<Integer, Map<String, Citation>> byPage = new HashMap<>();
        for (var entry : result.citations().entrySet()) {
            int page = entry.getValue().location().pageStart();
            byPage.computeIfAbsent(page, k -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
        }
        // Library is the authority on layout blocks — call PdfDocumentParser and consume its
        // per-block TextSections. The demo no longer detects blocks itself; layer separation
        // is enforced in code: parser → sections-with-BlockKind → demo just visualises them.
        var parsed = PdfDocumentParser.parse(pdfPath);
        var sectionsByPage = groupSectionsByPage(parsed);

        var written = new ArrayList<Path>(byPage.size());
        try (PDDocument pdf = Loader.loadPDF(pdfPath.toFile())) {
            var renderer = new PDFRenderer(pdf);
            for (var pageEntry : byPage.entrySet()) {
                int pageNumber = pageEntry.getKey();
                int pageIdx = pageNumber - 1; // PDFBox is 0-indexed internally
                if (pageIdx < 0 || pageIdx >= pdf.getNumberOfPages()) {
                    LOG.warn("citation page {} out of range (pdf has {} page(s)); skipping",
                            pageNumber, pdf.getNumberOfPages());
                    continue;
                }
                var pageImage = renderer.renderImageWithDPI(pageIdx, DPI);
                var positions = capturePageTextPositions(pdf, pageNumber);
                var librarySections = sectionsByPage.getOrDefault(pageNumber, List.of());
                var composed = composeWithSidebar(pageImage, positions, pageEntry.getValue(), librarySections);
                Path out = outputDir.resolve("page-" + pageNumber + ".png");
                ImageIO.write(composed, "PNG", out.toFile());
                written.add(out);
            }
        }
        LOG.info("rendered {} evidence overlay(s) for pdf={}", written.size(), pdfPath);
        return List.copyOf(written);
    }

    private static Map<Integer, List<TextSection>> groupSectionsByPage(ParsedDocument doc) {
        var byPage = new HashMap<Integer, List<TextSection>>();
        for (var section : doc.sections()) {
            if (section instanceof TextSection ts) {
                byPage.computeIfAbsent(ts.location().pageStart(), k -> new ArrayList<>()).add(ts);
            }
        }
        return byPage;
    }

    /** Capture every TextPosition for one page so we can map (charOffset → bounding box). */
    private static List<TextPosition> capturePageTextPositions(PDDocument pdf, int pageNumber)
            throws IOException {
        var positions = new ArrayList<TextPosition>();
        var stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition text) {
                positions.add(text);
                super.processTextPosition(text);
            }
        };
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        stripper.getText(pdf); // drives extraction; return text is unused — we want positions.
        return positions;
    }

    /**
     * Compose the rendered PDF page with a right-side sidebar that hosts the citation
     * legend. The page area gets only translucent highlight rectangles + small numbered
     * badges; field names + matchScores live entirely in the sidebar. This means the
     * overlay never obscures the original document text — readability of the source is
     * preserved while the audit chain is fully visible.
     */
    private static BufferedImage composeWithSidebar(
            BufferedImage pageImage,
            List<TextPosition> positions,
            Map<String, Citation> citations,
            List<TextSection> librarySections) {
        int composedW = pageImage.getWidth() + SIDEBAR_WIDTH;
        int composedH = pageImage.getHeight();
        var composed = new BufferedImage(composedW, composedH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composed.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        try {
            // White canvas, then paste the rendered page on the left.
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, composedW, composedH);
            g.drawImage(pageImage, 0, 0, null);

            // Reconstruct page text from the position list so indexOf offsets line up exactly
            // with the position-list indices we walk back through.
            var sb = new StringBuilder();
            for (var tp : positions) sb.append(tp.getUnicode());
            String pageText = sb.toString();

            // === Layer-of-responsibility split, enforced in code ===
            // The LIBRARY (PdfDocumentParser) detects + classifies blocks. The DEMO consumes
            // its output and just visualises. Each TextSection's `kind` drives the outline
            // style: HEADING is darker + thicker, LIST is indented, BODY is the baseline,
            // OTHER is the lightest. NO classification logic in the demo any more.
            // Background-luminance sampling picks light-on-dark / dark-on-light variant per
            // block — designed templates with dark sidebars (Yusuf Haris template, Umar Faruqy
            // template) get readable overlays without OCR-style image preprocessing (which is
            // irrelevant for born-digital PDFs — text is data, not pixels).
            for (var section : librarySections) {
                Rect box = findBoundingBoxByText(positions, section.text());
                if (box == null) continue; // text not located on page — skip silently
                boolean darkBg = isDarkBackground(pageImage, box);
                drawLayoutOutlineByKind(g, box, section.kind(), darkBg);
            }

            var entries = new ArrayList<LegendEntry>();
            int badge = 1;
            for (var entry : citations.entrySet()) {
                String fieldPath = entry.getKey();
                Citation citation = entry.getValue();
                Color color = colorForField(fieldPath);
                int start = pageText.indexOf(citation.exactQuote());
                if (start < 0) {
                    LOG.warn("citation quote not found in page text field={} quote={}",
                            fieldPath, abbreviate(citation.exactQuote()));
                    continue;
                }
                Rect box = findBoundingBox(positions, start, start + citation.exactQuote().length());
                if (box == null) {
                    LOG.warn("could not derive bounding box field={}", fieldPath);
                    continue;
                }
                drawHighlight(g, box, color);
                drawNumberedBadge(g, box, color, badge);
                entries.add(new LegendEntry(badge, fieldPath, color, citation.exactQuote(), citation.matchScore()));
                badge++;
            }
            drawSidebar(g, pageImage.getWidth(), composedH, entries, librarySections);
        } finally {
            g.dispose();
        }
        return composed;
    }

    /**
     * Walk the position list across the char-offset range and accumulate the union of every
     * TextPosition's bounding box. PDFBox's getYDirAdj is already top-down (image-coordinate)
     * space, so no flip is needed; we only multiply by SCALE to map user-space to pixels.
     */
    private static Rect findBoundingBox(List<TextPosition> positions, int start, int end) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        int cursor = 0;
        boolean any = false;
        for (var tp : positions) {
            int unicodeLen = tp.getUnicode() == null ? 0 : tp.getUnicode().length();
            int posStart = cursor;
            int posEnd = cursor + unicodeLen;
            cursor = posEnd;
            if (posEnd <= start || posStart >= end) continue;
            float x0 = tp.getXDirAdj(), y0 = tp.getYDirAdj() - tp.getHeightDir();
            float x1 = x0 + tp.getWidthDirAdj(), y1 = tp.getYDirAdj();
            if (x0 < minX) minX = x0;
            if (y0 < minY) minY = y0;
            if (x1 > maxX) maxX = x1;
            if (y1 > maxY) maxY = y1;
            any = true;
        }
        if (!any) return null;
        return new Rect(
                Math.round(minX * SCALE), Math.round(minY * SCALE),
                Math.round((maxX - minX) * SCALE), Math.round((maxY - minY) * SCALE));
    }

    /** Deterministic per-field colour: HSL hue from hashCode, fixed S/L for legibility. */
    private static Color colorForField(String fieldPath) {
        int h = fieldPath.hashCode();
        float hue = (((h % 360) + 360) % 360) / 360f;
        return Color.getHSBColor(hue, 0.7f, 0.85f);
    }

    private static void drawHighlight(Graphics2D g, Rect box, Color color) {
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 80));
        g.fillRect(box.x, box.y, box.w, box.h);
        g.setColor(color);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(box.x, box.y, box.w, box.h);
    }

    /** Locate the page-pixel bounding box for an arbitrary text snippet by reconstructing the
     *  page text from the position list and walking back through the matched offset range.
     *  This is the demo's read-only counterpart to the library's classifier — used so we can
     *  visualise the library's already-classified TextSections in pixel-space. */
    private static Rect findBoundingBoxByText(List<TextPosition> positions, String snippet) {
        if (snippet == null || snippet.isBlank()) return null;
        var sb = new StringBuilder();
        for (var tp : positions) sb.append(tp.getUnicode() == null ? "" : tp.getUnicode());
        String full = sb.toString();
        int idx = full.indexOf(snippet);
        if (idx < 0) {
            // Trim and retry — library text often has trimmed leading/trailing whitespace.
            String trimmed = snippet.strip();
            if (!trimmed.isEmpty() && !trimmed.equals(snippet)) idx = full.indexOf(trimmed);
            if (idx < 0) return null;
        }
        return findBoundingBox(positions, idx, idx + snippet.length());
    }

    /** Render a layout outline whose style encodes the BlockKind: HEADING gets a thicker dark
     *  dashed border, LIST gets a slight left indent, BODY is the baseline dashed grey, OTHER
     *  is the faintest. Outline NEVER fills (no obscuring of original content). The {@code
     *  darkBg} flag inverts the colour palette so designs with dark sidebars (e.g. Yusuf
     *  Haris template) still get a readable overlay. */
    private static void drawLayoutOutlineByKind(Graphics2D g, Rect box, BlockKind kind, boolean darkBg) {
        int pad = 4;
        Color color;
        float strokeW;
        float[] dashes;
        int leftPad = pad;
        switch (kind) {
            case HEADING -> {
                color = darkBg ? new Color(255, 230, 130) : new Color(60, 60, 80);
                strokeW = 2.0f;
                dashes = new float[] {8f, 4f};
            }
            case LIST -> {
                color = darkBg ? new Color(220, 220, 230) : new Color(120, 120, 135);
                strokeW = 1.5f;
                dashes = new float[] {3f, 3f};
                leftPad = pad + 6;
            }
            case BODY -> {
                color = darkBg ? new Color(220, 220, 230) : new Color(120, 120, 135);
                strokeW = 1.5f;
                dashes = new float[] {6f, 4f};
            }
            default -> {
                color = darkBg ? new Color(180, 180, 200) : new Color(180, 180, 190);
                strokeW = 1.0f;
                dashes = new float[] {3f, 5f};
            }
        }
        g.setColor(color);
        g.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, dashes, 0f));
        g.drawRect(box.x - leftPad, box.y - pad, box.w + pad + leftPad, box.h + pad * 2);
    }

    /** Sample average ITU-R BT.601 luminance under the box; true means we should use a
     *  light-coloured outline because the page background under this block is dark. */
    private static boolean isDarkBackground(BufferedImage pageImage, Rect box) {
        int x0 = Math.max(0, box.x);
        int y0 = Math.max(0, box.y);
        int x1 = Math.min(pageImage.getWidth() - 1, box.x + box.w);
        int y1 = Math.min(pageImage.getHeight() - 1, box.y + box.h);
        if (x1 <= x0 || y1 <= y0) return false;
        long lumSum = 0;
        int samples = 0;
        int areaPixels = (x1 - x0) * (y1 - y0);
        int targetSamples = 64;
        int step = Math.max(1, (int) Math.sqrt((double) areaPixels / targetSamples));
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int rgb = pageImage.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                lumSum += (r * 299 + g * 587 + b * 114) / 1000;
                samples++;
            }
        }
        if (samples == 0) return false;
        long avgLum = lumSum / samples;
        return avgLum < 110; // threshold: dark navy / charcoal sidebars trigger inverse palette
    }

    /** Group TextPositions into "layout blocks" — kept for legacy callers; the demo now
     *  consumes the library's TextSections directly. Retained here only so callers that
     *  insist on demo-side detection still have an option. */
    @SuppressWarnings("unused")
    private static List<Rect> detectLayoutBlocks(List<TextPosition> positions) {
        var blocks = new ArrayList<Rect>();
        if (positions.isEmpty()) return blocks;

        // Estimate typical line height from the median character height, in PDF user-space.
        float[] heights = new float[positions.size()];
        for (int i = 0; i < positions.size(); i++) heights[i] = positions.get(i).getHeightDir();
        java.util.Arrays.sort(heights);
        float lineHeight = Math.max(heights[heights.length / 2], 8f);
        float blockGap = lineHeight * 1.5f;

        // Walk positions in reading order; start a new block when Y advances by > blockGap.
        float bMinX = Float.POSITIVE_INFINITY, bMinY = Float.POSITIVE_INFINITY;
        float bMaxX = Float.NEGATIVE_INFINITY, bMaxY = Float.NEGATIVE_INFINITY;
        float lastY = -1f;
        boolean inBlock = false;
        for (var tp : positions) {
            if (tp.getUnicode() == null || tp.getUnicode().isBlank()) continue;
            float yTop = tp.getYDirAdj() - tp.getHeightDir();
            float yBot = tp.getYDirAdj();
            float xL = tp.getXDirAdj();
            float xR = tp.getXDirAdj() + tp.getWidthDirAdj();
            boolean breakBlock = inBlock && (yTop - lastY > blockGap || yTop < lastY - lineHeight);
            if (breakBlock) {
                blocks.add(scale(bMinX, bMinY, bMaxX, bMaxY));
                bMinX = Float.POSITIVE_INFINITY; bMinY = Float.POSITIVE_INFINITY;
                bMaxX = Float.NEGATIVE_INFINITY; bMaxY = Float.NEGATIVE_INFINITY;
                inBlock = false;
            }
            if (xL < bMinX) bMinX = xL;
            if (yTop < bMinY) bMinY = yTop;
            if (xR > bMaxX) bMaxX = xR;
            if (yBot > bMaxY) bMaxY = yBot;
            lastY = yBot;
            inBlock = true;
        }
        if (inBlock) blocks.add(scale(bMinX, bMinY, bMaxX, bMaxY));
        return blocks;
    }

    private static Rect scale(float minX, float minY, float maxX, float maxY) {
        return new Rect(
                Math.round(minX * SCALE), Math.round(minY * SCALE),
                Math.round((maxX - minX) * SCALE), Math.round((maxY - minY) * SCALE));
    }

    // (drawLayoutOutline replaced by drawLayoutOutlineByKind above — kind-aware styling)

    /** Small filled colored circle with a white digit at the top-left corner of the highlight.
     *  Half the circle sits outside the box so the badge is visible even when the box hugs
     *  the page text — the original PDF content underneath stays uncovered. */
    private static void drawNumberedBadge(Graphics2D g, Rect box, Color color, int number) {
        int diameter = 18;
        int cx = box.x;
        int cy = box.y;
        // Filled colored disc
        g.setColor(color);
        g.fillOval(cx - diameter / 2, cy - diameter / 2, diameter, diameter);
        // Thin black outline so the badge stands out against the page
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1f));
        g.drawOval(cx - diameter / 2, cy - diameter / 2, diameter, diameter);
        // White digit, centered
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        var fm = g.getFontMetrics();
        String s = Integer.toString(number);
        int tw = fm.stringWidth(s);
        g.setColor(Color.WHITE);
        g.drawString(s, cx - tw / 2, cy + fm.getAscent() / 2 - 2);
    }

    /** Right-side legend panel listing every numbered citation with its field name, score,
     *  and (truncated) source quote. This is the ONLY place auditor-readable text lives —
     *  it never overlaps the source page content. Also surfaces the layout-block count
     *  so reviewers can see how many regions the LIBRARY identified vs how many the LLM
     *  decided to cite (semantic vs structural separation). */
    private static void drawSidebar(
            Graphics2D g,
            int pageWidth,
            int pageHeight,
            List<LegendEntry> entries,
            List<TextSection> librarySections) {
        int x0 = pageWidth, y0 = 0, w = SIDEBAR_WIDTH, h = pageHeight;
        // Subtle background + left divider
        g.setColor(new Color(248, 248, 250));
        g.fillRect(x0, y0, w, h);
        g.setColor(Color.LIGHT_GRAY);
        g.drawLine(x0, y0, x0, y0 + h);
        // Header
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("Evidence — " + entries.size() + " citation(s)", x0 + 16, 28);
        // Sub-header: how many layout blocks the library detected, broken down by kind.
        // Kinds are GEOMETRIC (HEADING/BODY/LIST/OTHER) — the library's structural verdict.
        // The LLM separately decides which of these blocks carries which semantic field.
        var kindCounts = new java.util.EnumMap<BlockKind, Integer>(BlockKind.class);
        for (var s : librarySections) kindCounts.merge(s.kind(), 1, Integer::sum);
        g.setColor(new Color(120, 120, 130));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.drawString("Library detected " + librarySections.size() + " layout block(s):",
                x0 + 16, 50);
        var sbk = new StringBuilder();
        for (var k : BlockKind.values()) {
            int c = kindCounts.getOrDefault(k, 0);
            if (c == 0) continue;
            if (sbk.length() > 0) sbk.append("  ");
            sbk.append(k.name()).append("=").append(c);
        }
        g.drawString(sbk.toString(), x0 + 16, 66);
        g.setColor(Color.LIGHT_GRAY);
        g.drawLine(x0 + 16, 76, x0 + w - 16, 76);

        int rowY = 92;
        int rowH = 64;
        for (var e : entries) {
            // Numbered colored disc, mirroring the on-page badge
            int badgeD = 22;
            int bx = x0 + 16, by = rowY;
            g.setColor(e.color);
            g.fillOval(bx, by, badgeD, badgeD);
            g.setColor(Color.BLACK);
            g.drawOval(bx, by, badgeD, badgeD);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            var fm = g.getFontMetrics();
            String n = Integer.toString(e.number);
            int nw = fm.stringWidth(n);
            g.setColor(Color.WHITE);
            g.drawString(n, bx + (badgeD - nw) / 2, by + badgeD / 2 + fm.getAscent() / 2 - 2);

            // Field name + match score
            int textX = bx + badgeD + 10;
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.drawString(e.fieldPath, textX, rowY + 14);
            g.setColor(new Color(80, 80, 80));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.drawString(String.format("matchScore %.2f", e.matchScore), textX, rowY + 30);
            g.drawString("\"" + abbreviate(e.quote, 36) + "\"", textX, rowY + 46);

            rowY += rowH;
            if (rowY > h - 40) break;
        }

        // Footer hint
        g.setColor(new Color(140, 140, 140));
        g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 10));
        g.drawString("doctruth.ai — every field cites its source", x0 + 16, h - 16);
    }

    /** Carrier for one row of the sidebar legend. */
    private record LegendEntry(int number, String fieldPath, Color color, String quote, double matchScore) {}

    /** Pixel-space rectangle (image coordinates, top-down). */
    private record Rect(int x, int y, int w, int h) {}

    private static String abbreviate(String s) {
        return abbreviate(s, 60);
    }

    private static String abbreviate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    // ---------------------------------------------------------------------
    // Demo main
    // ---------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        Path fixtureDir = Files.isDirectory(Path.of("fixtures/pdf"))
                ? Path.of("fixtures/pdf") : Path.of("/Users/jameslee/java-doc-truth/fixtures/pdf");
        Path outputBase = Files.isDirectory(Path.of("examples/evidence-overlay"))
                ? Path.of("examples/evidence-overlay/output")
                : Path.of("/Users/jameslee/java-doc-truth/examples/evidence-overlay/output");

        var picks = pickDemoFixtures(fixtureDir, count);
        if (picks.isEmpty()) {
            throw new IllegalStateException("no fixture PDF with diverse layout + quotable content in " + fixtureDir);
        }
        System.out.println("Rendering evidence overlays for " + picks.size() + " fixture(s):");

        for (int i = 0; i < picks.size(); i++) {
            var picked = picks.get(i);
            var quotes = pickDemoQuotes(picked.firstPageText());
            var citations = new LinkedHashMap<String, Citation>();
            addCitation(citations, "fullName", quotes.headline, picked.firstPageText());
            addCitation(citations, "email", quotes.email, picked.firstPageText());
            addCitation(citations, "sectionHeader", quotes.allCaps, picked.firstPageText());
            if (citations.isEmpty()) {
                System.out.println("  [skipped] no quotable content in " + picked.path().getFileName());
                continue;
            }
            var demoValue = new ResumeFields(
                    quotes.headline == null ? "" : quotes.headline,
                    quotes.email == null ? "" : quotes.email,
                    quotes.allCaps == null ? "" : quotes.allCaps);
            var provenance = new Provenance("demo", "evidence-overlay-v0", Instant.now(),
                    Optional.empty(), Optional.empty(), Optional.empty(), 0);
            var result = new ExtractionResult<>(demoValue, citations, Map.<String, Confidence>of(), provenance);

            // One subdir per fixture so multi-run outputs don't overwrite each other.
            String stem = stem(picked.path().getFileName().toString());
            Path outputDir = outputBase.resolve(stem);
            var paths = render(picked.path(), result, outputDir);

            // Library kind breakdown for this fixture
            var parsed = PdfDocumentParser.parse(picked.path());
            var page1 = parsed.sections().stream()
                    .filter(s -> s instanceof TextSection)
                    .map(s -> (TextSection) s)
                    .filter(ts -> ts.location().pageStart() == 1)
                    .toList();
            var kinds = new java.util.EnumMap<BlockKind, Integer>(BlockKind.class);
            for (var s : page1) kinds.merge(s.kind(), 1, Integer::sum);

            System.out.printf("%n[%d/%d] %s%n", i + 1, picks.size(), picked.path().getFileName());
            System.out.println("  blocks: " + page1.size() + " " + kinds + "; cited: " + citations.size());
            for (var p : paths) System.out.println("  → " + p);
            for (var e : citations.entrySet()) {
                System.out.printf("      [%-15s] \"%s\" score=%.2f%n",
                        e.getKey(), abbreviate(e.getValue().exactQuote(), 50),
                        e.getValue().matchScore());
            }
        }
    }

    private static String stem(String filename) {
        int dot = filename.lastIndexOf('.');
        String s = dot > 0 ? filename.substring(0, dot) : filename;
        // Sanitise — keep filesystem-safe, ≤ 80 chars
        s = s.replaceAll("[^a-zA-Z0-9._-]", "_");
        return s.length() <= 80 ? s : s.substring(0, 80);
    }

    private static void addCitation(Map<String, Citation> out, String field, String quote, String pageText) {
        if (quote == null || quote.isBlank()) return;
        out.put(field, new Citation(loc(pageText, quote), quote, 1.0));
    }

    private static SourceLocation loc(String pageText, String quote) {
        int idx = pageText.indexOf(quote);
        if (idx < 0) idx = 0;
        // Best-effort line approximation: count newlines up to the offset.
        int line = 1 + (int) pageText.substring(0, idx).chars().filter(c -> c == '\n').count();
        return new SourceLocation(1, 1, line, line, idx);
    }

    /** Pick up to {@code count} distinct fixtures meeting the demo's diversity criteria. */
    private static List<PickedFixture> pickDemoFixtures(Path fixtureDir, int count) throws IOException {
        var picks = new ArrayList<PickedFixture>();
        try (Stream<Path> stream = Files.list(fixtureDir)) {
            var pdfs = stream.filter(p -> p.toString().toLowerCase().endsWith(".pdf")).sorted().toList();
            for (var pdf : pdfs) {
                if (picks.size() >= count) break;
                try {
                    ParsedDocument doc = PdfDocumentParser.parse(pdf);
                    var page1Sections = doc.sections().stream()
                            .filter(s -> s instanceof TextSection)
                            .map(s -> (TextSection) s)
                            .filter(ts -> ts.location().pageStart() == 1)
                            .toList();
                    if (page1Sections.isEmpty()) continue;
                    String pageText = page1Sections.stream()
                            .map(TextSection::text)
                            .reduce((a, b) -> a + "\n" + b)
                            .orElse("");
                    if (pageText.length() < 500) continue;
                    long distinctKinds = page1Sections.stream()
                            .map(TextSection::kind)
                            .distinct()
                            .count();
                    if (distinctKinds < 2) continue;
                    var quotes = pickDemoQuotes(pageText);
                    if (quotes.headline == null && quotes.email == null && quotes.allCaps == null) continue;
                    picks.add(new PickedFixture(pdf, pageText));
                } catch (Exception e) {
                    LOG.debug("skipping unparseable fixture {}: {}", pdf, e.getMessage());
                }
            }
        }
        return picks;
    }

    /** Picks the first fixture whose page-1 has > 500 chars of text AND ≥ 2 distinct
     *  {@link BlockKind} values across the page — that diversity is what makes the overlay
     *  visually interesting (HEADING + BODY in different outline styles). Skips PDFs whose
     *  layout is too uniform (e.g. tightly-packed Chinese resumes that defeat Y-gap detection)
     *  and PDFs whose ASCII-pattern content is too thin for the demo's quote picks. */
    @SuppressWarnings("unused")
    private static Optional<PickedFixture> pickDemoFixture(Path fixtureDir) throws IOException {
        try (Stream<Path> stream = Files.list(fixtureDir)) {
            var pdfs = stream.filter(p -> p.toString().toLowerCase().endsWith(".pdf")).sorted().toList();
            for (var pdf : pdfs) {
                try {
                    ParsedDocument doc = PdfDocumentParser.parse(pdf);
                    // Concatenate page-1 text across ALL detected blocks (not just the first
                    // block, which after the layout-block upgrade is typically a short heading).
                    var page1Sections = doc.sections().stream()
                            .filter(s -> s instanceof TextSection)
                            .map(s -> (TextSection) s)
                            .filter(ts -> ts.location().pageStart() == 1)
                            .toList();
                    if (page1Sections.isEmpty()) continue;
                    String pageText = page1Sections.stream()
                            .map(TextSection::text)
                            .reduce((a, b) -> a + "\n" + b)
                            .orElse("");
                    if (pageText.length() < 500) continue;
                    long distinctKinds = page1Sections.stream()
                            .map(TextSection::kind)
                            .distinct()
                            .count();
                    if (distinctKinds < 2) continue; // need visual variety for the demo
                    var quotes = pickDemoQuotes(pageText);
                    if (quotes.headline == null && quotes.email == null && quotes.allCaps == null) continue;
                    return Optional.of(new PickedFixture(pdf, pageText));
                } catch (Exception e) {
                    LOG.debug("skipping unparseable fixture {}: {}", pdf, e.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    private record PickedFixture(Path path, String firstPageText) {}

    private record DemoQuotes(String headline, String email, String allCaps) {}

    /** Pick three distinctive substrings from the page text that a Citation could realistically point at. */
    private static DemoQuotes pickDemoQuotes(String text) {
        String email = null, allCaps = null;
        Matcher m = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+").matcher(text);
        if (m.find()) email = m.group();
        Matcher caps = Pattern.compile("\\b[A-Z][A-Z0-9 ]{8,40}[A-Z0-9]\\b").matcher(text);
        if (caps.find()) allCaps = caps.group().strip();
        // Headline: prefer "<KeyWord(s)>: <Capital Word> <Capital Word>..." patterns —
        // matches "Nama Penuh: Amy Bin Muhammad" / "Full Name: Jane Doe" / "Name: John Smith".
        // Robust to BODY blocks that PDFBox concatenates without newlines.
        String headline = null;
        Matcher named = Pattern.compile(
                        "([A-Za-z]+(?:\\s+[A-Za-z]+)?:\\s+[A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)+)")
                .matcher(text);
        if (named.find()) headline = named.group(1).strip();
        // Fallback: first non-blank short line that isn't the all-caps heading or an email.
        if (headline == null) {
            for (var line : text.lines().toList()) {
                var t = line.strip();
                if (t.length() < 10 || t.length() > 60) continue;
                if (allCaps != null && t.equals(allCaps)) continue;
                if (email != null && t.contains(email)) continue;
                headline = t;
                break;
            }
        }
        return new DemoQuotes(headline, email, allCaps);
    }
}
