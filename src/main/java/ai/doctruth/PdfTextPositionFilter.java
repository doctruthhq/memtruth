package ai.doctruth;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.apache.pdfbox.text.TextPosition;

final class PdfTextPositionFilter {

    private static final double TEXT_MIN_HEIGHT = 1.0;
    private static final double MIN_DUPLICATE_INTERSECTION = 0.5;
    private static final double MIN_CONTAINED_FRAGMENT_INTERSECTION = 0.8;
    private static final double BASELINE_BAND_RATIO = 0.6;
    private static final double HORIZONTAL_CONTAINMENT_TOLERANCE = 1.0;
    private static final double BACKGROUND_WIDE_RATIO = 0.5;
    private static final double BACKGROUND_TALL_RATIO = 0.5;
    private static final double BACKGROUND_MINOR_RATIO = 0.1;
    private static final double HIGH_REPLACEMENT_CHARACTER_RATIO = 0.3;
    private static final char REPLACEMENT_CHARACTER = '\uFFFD';
    private static final Pattern CONSECUTIVE_SPACES = Pattern.compile(" {2,}");

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
                .map(PdfTextPositionFilter::normalizeText)
                .filter(box -> isUsable(box, pageWidth, pageHeight))
                .toList();
        return removeDuplicateBoxes(usable);
    }

    static double replacementCharacterRatio(List<TextBox> boxes) {
        int total = 0;
        int replacements = 0;
        for (var box : boxes.stream().map(PdfTextPositionFilter::normalizeText).toList()) {
            total += box.text().length();
            replacements += replacementCharacterCount(box.text());
        }
        return total == 0 ? 0.0 : (double) replacements / total;
    }

    static boolean hasHighReplacementCharacterRatio(List<TextBox> boxes) {
        return replacementCharacterRatio(boxes) >= HIGH_REPLACEMENT_CHARACTER_RATIO;
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
        var boxes = boxes(candidates);
        for (int i = 0; i < candidates.size(); i++) {
            var candidate = candidates.get(i);
            if (shouldKeep(candidate.box(), boxes, i)) {
                out.add(candidate);
            }
        }
        return List.copyOf(out);
    }

    private static List<TextBox> removeDuplicateBoxes(List<TextBox> boxes) {
        var out = new ArrayList<TextBox>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            var box = boxes.get(i);
            if (shouldKeep(box, boxes, i)) {
                out.add(box);
            }
        }
        return List.copyOf(out);
    }

    private static List<TextBox> boxes(List<PositionCandidate> candidates) {
        return candidates.stream().map(PositionCandidate::box).toList();
    }

    private static boolean shouldKeep(TextBox candidate, List<TextBox> boxes, int candidateIndex) {
        for (int i = 0; i < boxes.size(); i++) {
            if (i == candidateIndex) {
                continue;
            }
            var other = boxes.get(i);
            if (sameOverlappingText(other, candidate) && i < candidateIndex) {
                return false;
            }
            if (containsOverlappingFragment(other, candidate)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameOverlappingText(TextBox first, TextBox second) {
        return Objects.equals(first.text(), second.text())
                && close(first.width(), second.width())
                && close(first.height(), second.height())
                && intersectionPercent(first, second) > MIN_DUPLICATE_INTERSECTION;
    }

    private static boolean containsOverlappingFragment(TextBox larger, TextBox fragment) {
        return larger.text().length() > fragment.text().length()
                && containsTextToken(larger.text(), fragment.text())
                && (intersectionPercent(larger, fragment) >= MIN_CONTAINED_FRAGMENT_INTERSECTION
                        || sameBaselineAndHorizontallyContained(larger, fragment));
    }

    private static boolean containsTextToken(String larger, String fragment) {
        int index = larger.indexOf(fragment);
        while (index >= 0) {
            int end = index + fragment.length();
            if (isTokenBoundary(larger, index - 1) && isTokenBoundary(larger, end)) {
                return true;
            }
            index = larger.indexOf(fragment, index + 1);
        }
        return false;
    }

    private static boolean isTokenBoundary(String text, int index) {
        return index < 0 || index >= text.length() || !Character.isLetterOrDigit(text.charAt(index));
    }

    private static boolean sameBaselineAndHorizontallyContained(TextBox larger, TextBox fragment) {
        double band = Math.max(larger.height(), fragment.height()) * BASELINE_BAND_RATIO;
        return Math.abs(larger.y() - fragment.y()) <= band && horizontallyContains(larger, fragment);
    }

    private static boolean horizontallyContains(TextBox larger, TextBox fragment) {
        return fragment.x() + HORIZONTAL_CONTAINMENT_TOLERANCE >= larger.x()
                && fragment.x() + fragment.width() <= larger.x() + larger.width() + HORIZONTAL_CONTAINMENT_TOLERANCE;
    }

    // Adapted from OpenDataLoader's TextProcessor/ContentFilterProcessor text chunk cleanup order.
    private static TextBox normalizeText(TextBox box) {
        var text = box.text() == null ? "" : box.text().strip();
        return new TextBox(compressConsecutiveSpaces(text), box.x(), box.y(), box.width(), box.height());
    }

    private static String compressConsecutiveSpaces(String text) {
        return CONSECUTIVE_SPACES.matcher(text).replaceAll(" ");
    }

    private static int replacementCharacterCount(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == REPLACEMENT_CHARACTER) {
                count++;
            }
        }
        return count;
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
                    normalizeText(new TextBox(
                            position.getUnicode(),
                            position.getXDirAdj(),
                            position.getYDirAdj(),
                            position.getWidthDirAdj(),
                            position.getHeightDir())));
        }
    }
}
