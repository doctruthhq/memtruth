package ai.doctruth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.apache.pdfbox.text.TextPosition;

final class PdfTextPositionBoxes {

    private static final double PAGE_SCALE = 1000.0;
    private static final double BASELINE_EPSILON = 2.0;
    private static final double LINE_ASCENT_FACTOR = 1.67;
    private static final double LINE_DESCENT_FACTOR = 0.31;

    private PdfTextPositionBoxes() {
        throw new AssertionError("no instances");
    }

    static Optional<BoundingBox> layoutBox(List<TextPosition> positions, double pageWidth, double pageHeight) {
        var lines = groupByBaseline(nonBlank(positions));
        if (lines.isEmpty() || pageWidth <= 0.0 || pageHeight <= 0.0) {
            return Optional.empty();
        }
        return scale(combine(lines.stream().map(PdfTextPositionBoxes::lineBox).toList()), pageWidth, pageHeight);
    }

    private static List<TextPosition> nonBlank(List<TextPosition> positions) {
        return positions.stream()
                .filter(position ->
                        position.getUnicode() != null && !position.getUnicode().isBlank())
                .sorted(Comparator.comparingDouble(TextPosition::getYDirAdj)
                        .thenComparingDouble(TextPosition::getXDirAdj))
                .toList();
    }

    private static List<List<TextPosition>> groupByBaseline(List<TextPosition> positions) {
        var lines = new ArrayList<List<TextPosition>>();
        var current = new ArrayList<TextPosition>();
        double currentY = Double.NaN;
        for (TextPosition position : positions) {
            double y = position.getYDirAdj();
            if (current.isEmpty() || Math.abs(y - currentY) <= BASELINE_EPSILON) {
                current.add(position);
            } else {
                lines.add(current);
                current = new ArrayList<>(List.of(position));
            }
            currentY = Double.isNaN(currentY) ? y : currentY;
        }
        if (!current.isEmpty()) {
            lines.add(current);
        }
        return lines;
    }

    private static RawBox lineBox(List<TextPosition> line) {
        double baseline =
                line.stream().mapToDouble(TextPosition::getYDirAdj).max().orElseThrow();
        double height =
                line.stream().mapToDouble(TextPosition::getHeightDir).max().orElseThrow();
        double x0 = line.stream().mapToDouble(TextPosition::getXDirAdj).min().orElseThrow();
        double x1 = line.stream()
                .mapToDouble(position -> position.getXDirAdj() + position.getWidthDirAdj())
                .max()
                .orElseThrow();
        return new RawBox(x0, baseline - LINE_ASCENT_FACTOR * height, x1, baseline + LINE_DESCENT_FACTOR * height);
    }

    private static RawBox combine(List<RawBox> boxes) {
        return new RawBox(
                boxes.stream().mapToDouble(RawBox::x0).min().orElseThrow(),
                boxes.stream().mapToDouble(RawBox::y0).min().orElseThrow(),
                boxes.stream().mapToDouble(RawBox::x1).max().orElseThrow(),
                boxes.stream().mapToDouble(RawBox::y1).max().orElseThrow());
    }

    private static Optional<BoundingBox> scale(RawBox box, double pageWidth, double pageHeight) {
        double x0 = clamp(box.x0() * PAGE_SCALE / pageWidth);
        double y0 = clamp(box.y0() * PAGE_SCALE / pageHeight);
        double x1 = clamp(box.x1() * PAGE_SCALE / pageWidth);
        double y1 = clamp(box.y1() * PAGE_SCALE / pageHeight);
        if (x1 <= x0 || y1 <= y0) {
            return Optional.empty();
        }
        return Optional.of(new BoundingBox(x0, y0, x1, y1));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(PAGE_SCALE, value));
    }

    private record RawBox(double x0, double y0, double x1, double y1) {}
}
