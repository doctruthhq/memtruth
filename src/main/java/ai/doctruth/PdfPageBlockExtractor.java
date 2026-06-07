package ai.doctruth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

final class PdfPageBlockExtractor {

    private static final float MIN_LINE_HEIGHT = 8f;
    private static final float BLOCK_GAP_FACTOR = 1.5f;
    private static final double HEADING_HEIGHT_FACTOR = 1.15;
    private static final int ALLCAPS_MIN_LEN = 5;
    private static final int ALLCAPS_MAX_LEN = 60;
    private static final double DIGIT_HEAVY_RATIO = 0.30;
    private static final Pattern NUMBERED_LIST = Pattern.compile("^\\s*\\d+[.)]\\s+");
    private static final String LIST_BULLETS = "•▪*-·";

    private PdfPageBlockExtractor() {
        throw new AssertionError("no instances");
    }

    static List<PdfTextBlock> detectBlocksOnPage(PDDocument pdf, int pageNumber) throws IOException {
        var positions = capturePageTextPositions(pdf, pageNumber);
        if (positions.isEmpty()) {
            return List.of();
        }
        double medianHeight = medianHeight(positions);
        var page = pdf.getPage(pageNumber - 1);
        var separators = PdfPageGraphicsExtractor.extractHorizontalSeparators(page);
        var groups = PdfVisualTextLayout.groupByColumnsAndTypography(
                positions, estimateLineSpacing(positions, medianHeight), medianHeight, separators);
        var mediaBox = page.getMediaBox();
        return renderBlocks(pageNumber, positions, groups, medianHeight, mediaBox.getWidth(), mediaBox.getHeight());
    }

    private static List<TextPosition> capturePageTextPositions(PDDocument pdf, int pageNumber) throws IOException {
        var positions = new ArrayList<TextPosition>();
        var stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                positions.addAll(textPositions);
            }
        };
        stripper.setSortByPosition(true);
        stripper.setSuppressDuplicateOverlappingText(true);
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        stripper.getText(pdf);
        return positions;
    }

    private static List<PdfTextBlock> renderBlocks(
            int pageNumber,
            List<TextPosition> positions,
            List<List<TextPosition>> groups,
            double medianHeight,
            double pageWidth,
            double pageHeight) {
        if (groups.isEmpty()) {
            return List.of();
        }
        String pageText = renderAll(positions);
        var out = new ArrayList<PdfTextBlock>(groups.size());
        int charCursor = 0;
        int lineCursor = 1;
        for (var group : groups) {
            String text = renderGroup(group);
            int lineCount = Math.max(1, (int) text.lines().count());
            int charOffset = clampOffset(pageText, text, charCursor);
            var loc = new SourceLocation(pageNumber, pageNumber, lineCursor, lineCursor + lineCount - 1, charOffset);
            out.add(new PdfTextBlock(
                    text,
                    classify(text, avgHeight(group), medianHeight),
                    loc,
                    PdfTextPositionBoxes.layoutBox(group, pageWidth, pageHeight)));
            charCursor = charOffset + text.length();
            lineCursor += lineCount;
        }
        return out;
    }

    private static boolean isBlank(TextPosition text) {
        String u = text.getUnicode();
        return u == null || u.isBlank();
    }

    private static String renderAll(List<TextPosition> positions) {
        var sb = new StringBuilder();
        for (var p : positions) {
            String u = p.getUnicode();
            if (u != null) {
                sb.append(u);
            }
        }
        return sb.toString();
    }

    private static String renderGroup(List<TextPosition> group) {
        return PdfVisualTextLayout.renderGroup(group);
    }

    private static double estimateLineSpacing(List<TextPosition> positions, double pageMedianHeight) {
        double upperBound = Math.max(pageMedianHeight * 2.0, MIN_LINE_HEIGHT);
        var deltas = collectPositiveBaselineDeltas(positions);
        if (deltas.length == 0) {
            return upperBound;
        }
        Arrays.sort(deltas);
        int idx = Math.max(0, deltas.length / 10);
        double lowest = Math.max(deltas[idx], MIN_LINE_HEIGHT);
        return Math.min(lowest, upperBound);
    }

    private static float[] collectPositiveBaselineDeltas(List<TextPosition> positions) {
        var out = new ArrayList<Float>();
        float lastBaseline = -1f;
        for (var p : positions) {
            if (!isBlank(p)) {
                lastBaseline = collectDelta(out, p.getYDirAdj(), lastBaseline);
            }
        }
        var arr = new float[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private static float collectDelta(List<Float> out, float baseline, float lastBaseline) {
        if (lastBaseline > 0f) {
            float delta = baseline - lastBaseline;
            if (delta > 0.5f) {
                out.add(delta);
            }
        }
        return baseline;
    }

    private static double medianHeight(List<TextPosition> positions) {
        var heights = new float[positions.size()];
        int n = 0;
        for (var p : positions) {
            float h = p.getHeightDir();
            if (h > 0f) {
                heights[n++] = h;
            }
        }
        if (n == 0) {
            return MIN_LINE_HEIGHT;
        }
        float[] trimmed = Arrays.copyOf(heights, n);
        Arrays.sort(trimmed);
        return Math.max(trimmed[n / 2], MIN_LINE_HEIGHT);
    }

    private static double avgHeight(List<TextPosition> group) {
        double sum = 0.0;
        int n = 0;
        for (var p : group) {
            float h = p.getHeightDir();
            if (h > 0f) {
                sum += h;
                n++;
            }
        }
        return n == 0 ? MIN_LINE_HEIGHT : sum / n;
    }

    private static int clampOffset(String pageText, String blockText, int searchFrom) {
        if (blockText.isEmpty()) {
            return Math.min(searchFrom, pageText.length());
        }
        int idx = pageText.indexOf(blockText, searchFrom);
        if (idx < 0) {
            idx = pageText.indexOf(blockText);
        }
        return idx < 0 ? Math.min(searchFrom, pageText.length()) : idx;
    }

    static BlockKind classify(String blockText, double avgCharHeight, double pageMedianHeight) {
        Objects.requireNonNull(blockText, "blockText");
        String trimmed = blockText.stripLeading();
        if (trimmed.isEmpty()) {
            return BlockKind.OTHER;
        }
        if (LIST_BULLETS.indexOf(trimmed.charAt(0)) >= 0
                || NUMBERED_LIST.matcher(blockText).find()) {
            return BlockKind.LIST;
        }
        if (pageMedianHeight > 0 && avgCharHeight > pageMedianHeight * HEADING_HEIGHT_FACTOR) {
            return BlockKind.HEADING;
        }
        return looksLikeAllCapsHeading(trimmed) ? BlockKind.HEADING : BlockKind.BODY;
    }

    private static boolean looksLikeAllCapsHeading(String trimmed) {
        String head = firstLine(trimmed);
        int len = head.length();
        if (len < ALLCAPS_MIN_LEN || len > ALLCAPS_MAX_LEN) {
            return false;
        }
        if (!head.equals(head.toUpperCase(Locale.ROOT))) {
            return false;
        }
        var counts = countLettersAndDigits(head);
        if (counts.letters() == 0) {
            return false;
        }
        return (double) counts.digits() / head.length() < DIGIT_HEAVY_RATIO;
    }

    private static String firstLine(String trimmed) {
        int newline = trimmed.indexOf('\n');
        return (newline >= 0 ? trimmed.substring(0, newline) : trimmed).strip();
    }

    private static CharCounts countLettersAndDigits(String text) {
        int digits = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                digits++;
            } else if (Character.isLetter(c)) {
                letters++;
            }
        }
        return new CharCounts(letters, digits);
    }

    private record CharCounts(int letters, int digits) {}
}
