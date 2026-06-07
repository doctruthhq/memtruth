package ai.doctruth;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.text.TextPosition;

final class PdfTextPositionMetrics {

    static final float MIN_LINE_HEIGHT = 8f;

    private PdfTextPositionMetrics() {
        throw new AssertionError("no instances");
    }

    static List<TextPosition> sortByX(List<TextPosition> positions) {
        return positions.stream()
                .sorted((left, right) -> Float.compare(left.getXDirAdj(), right.getXDirAdj()))
                .toList();
    }

    static double horizontalGap(TextPosition previous, TextPosition current) {
        return current.getXDirAdj() - (previous.getXDirAdj() + previous.getWidthDirAdj());
    }

    static double medianHeight(List<TextPosition> positions) {
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

    static double medianWidth(List<TextPosition> positions) {
        var widths = new float[positions.size()];
        int n = 0;
        for (var p : positions) {
            float w = p.getWidthDirAdj();
            if (w > 0f) {
                widths[n++] = w;
            }
        }
        if (n == 0) {
            return MIN_LINE_HEIGHT / 2.0;
        }
        float[] trimmed = Arrays.copyOf(widths, n);
        Arrays.sort(trimmed);
        return Math.max(trimmed[n / 2], 1.0);
    }

    static String renderWithInferredSpaces(List<TextPosition> positions) {
        var sb = new StringBuilder();
        TextPosition previous = null;
        for (var p : positions) {
            String unicode = p.getUnicode();
            if (unicode == null) {
                continue;
            }
            if (isBlank(p)) {
                appendSingleSpace(sb);
                previous = p;
                continue;
            }
            if (previous != null && !isBlank(previous) && horizontalGap(previous, p) > spaceThreshold(previous)) {
                appendSingleSpace(sb);
            }
            sb.append(unicode);
            previous = p;
        }
        return sb.toString().stripTrailing();
    }

    static boolean isBlank(TextPosition text) {
        String u = text.getUnicode();
        return u == null || u.isBlank();
    }

    static boolean isBold(TextPosition position) {
        var font = position.getFont();
        if (font == null || font.getName() == null) {
            return false;
        }
        return font.getName().toLowerCase(Locale.ROOT).contains("bold");
    }

    private static double spaceThreshold(TextPosition previous) {
        return Math.max(1.0, previous.getWidthDirAdj() * 0.25);
    }

    private static void appendSingleSpace(StringBuilder sb) {
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != ' ') {
            sb.append(' ');
        }
    }
}
