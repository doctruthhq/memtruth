package ai.doctruth;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.pdfbox.text.TextPosition;

final class PdfTextPositionFilter {

    private static final double TEXT_MIN_HEIGHT = 1.0;
    private static final double MIN_DUPLICATE_INTERSECTION = 0.5;
    private static final double BACKGROUND_WIDE_RATIO = 0.5;
    private static final double BACKGROUND_TALL_RATIO = 0.5;
    private static final double BACKGROUND_MINOR_RATIO = 0.1;

    private PdfTextPositionFilter() {
        throw new AssertionError("no instances");
    }

    static List<TextPosition> filter(List<TextPosition> positions, double pageWidth, double pageHeight) {
        var usable = positions.stream()
                .map(PositionCandidate::from)
                .filter(candidate -> isUsable(candidate.box(), pageWidth, pageHeight))
                .toList();
        return removeDuplicateOverlaps(usable).stream().map(PositionCandidate::position).toList();
    }

    static List<TextBox> filterBoxes(List<TextBox> boxes, double pageWidth, double pageHeight) {
        var usable = boxes.stream()
                .filter(box -> isUsable(box, pageWidth, pageHeight))
                .toList();
        return removeDuplicateBoxes(usable);
    }

    static boolean isUsable(TextBox box, double pageWidth, double pageHeight) {
        if (box.text() == null || box.text().isBlank() || isControlOnly(box.text())) {
            return false;
        }
        return finitePositive(box.width(), box.height())
                && box.height() > TEXT_MIN_HEIGHT
                && overlapsPage(box.x(), box.y(), box.width(), box.height(), pageWidth, pageHeight)
                && !isBackgroundSized(box, pageWidth, pageHeight);
    }

    private static List<PositionCandidate> removeDuplicateOverlaps(List<PositionCandidate> candidates) {
        var out = new ArrayList<PositionCandidate>(candidates.size());
        for (var candidate : candidates) {
            if (out.stream().noneMatch(existing -> sameOverlappingText(existing.box(), candidate.box()))) {
                out.add(candidate);
            }
        }
        return List.copyOf(out);
    }

    private static List<TextBox> removeDuplicateBoxes(List<TextBox> boxes) {
        var out = new ArrayList<TextBox>(boxes.size());
        for (var box : boxes) {
            if (out.stream().noneMatch(existing -> sameOverlappingText(existing, box))) {
                out.add(box);
            }
        }
        return List.copyOf(out);
    }

    private static boolean sameOverlappingText(TextBox first, TextBox second) {
        return Objects.equals(first.text(), second.text())
                && close(first.width(), second.width())
                && close(first.height(), second.height())
                && intersectionPercent(first, second) > MIN_DUPLICATE_INTERSECTION;
    }

    private static boolean isControlOnly(String unicode) {
        return unicode != null && !unicode.isEmpty() && unicode.codePoints().allMatch(Character::isISOControl);
    }

    private static boolean finitePositive(double width, double height) {
        return Double.isFinite(width) && Double.isFinite(height) && width > 0.0 && height > 0.0;
    }

    private static boolean overlapsPage(
            double x, double y, double width, double height, double pageWidth, double pageHeight) {
        return Double.isFinite(x)
                && Double.isFinite(y)
                && x + width > 0.0
                && y + height > 0.0
                && x < pageWidth
                && y < pageHeight;
    }

    private static boolean isBackgroundSized(TextBox box, double pageWidth, double pageHeight) {
        return pageWidth > 0.0
                && pageHeight > 0.0
                && ((box.width() > BACKGROUND_WIDE_RATIO * pageWidth
                                && box.height() > BACKGROUND_MINOR_RATIO * pageHeight)
                        || (box.width() > BACKGROUND_MINOR_RATIO * pageWidth
                                && box.height() > BACKGROUND_TALL_RATIO * pageHeight));
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) <= Math.max(0.5, Math.max(Math.abs(left), Math.abs(right)) * 0.05);
    }

    private static double intersectionPercent(TextBox first, TextBox second) {
        double x0 = Math.max(first.x(), second.x());
        double y0 = Math.max(first.y(), second.y());
        double x1 = Math.min(first.x() + first.width(), second.x() + second.width());
        double y1 = Math.min(first.y() + first.height(), second.y() + second.height());
        double intersection = Math.max(0.0, x1 - x0) * Math.max(0.0, y1 - y0);
        double firstArea = first.width() * first.height();
        double secondArea = second.width() * second.height();
        double denominator = Math.min(firstArea, secondArea);
        return denominator <= 0.0 ? 0.0 : intersection / denominator;
    }

    record TextBox(String text, double x, double y, double width, double height) {}

    private record PositionCandidate(TextPosition position, TextBox box) {
        static PositionCandidate from(TextPosition position) {
            return new PositionCandidate(
                    position,
                    new TextBox(
                            position.getUnicode(),
                            position.getXDirAdj(),
                            position.getYDirAdj(),
                            position.getWidthDirAdj(),
                            position.getHeightDir()));
        }
    }
}
