package ai.doctruth;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.pdfbox.text.TextPosition;

final class PdfVisualTextLayout {

    private static final double SAME_COLUMN_OVERLAP_RATIO = 0.20;
    private static final double COLUMN_PROXIMITY_FACTOR = 8.0;
    private static final double LINE_SEGMENT_GAP_FACTOR = 3.0;
    private static final float BLOCK_GAP_FACTOR = 1.5f;
    private static final double BASELINE_EPSILON = 2.0;
    private static final Pattern DATE_RANGE = Pattern.compile(
            ".*\\b(?:19|20)\\d{2}\\b\\s*(?:[-–—]|to)\\s*(?:\\b(?:19|20)\\d{2}\\b|present|now).*",
            Pattern.CASE_INSENSITIVE);

    private PdfVisualTextLayout() {
        throw new AssertionError("no instances");
    }

    static List<List<TextPosition>> groupByColumnsAndTypography(
            List<TextPosition> positions, double pageMedianHeight, double medianHeight,
            List<PdfPageGraphicsExtractor.HorizontalSeparator> separators) {
        var lineSegments = splitIntoLineSegments(positions, medianHeight);
        if (lineSegments.isEmpty()) {
            return List.of();
        }
        var columns = inferColumns(lineSegments, medianHeight);
        for (var line : lineSegments) {
            line.columnIndex = columnIndexFor(line, columns, medianHeight);
        }
        attachInlineDateSegments(lineSegments);
        attachInlineFieldValueSegments(lineSegments);
        lineSegments.sort(PdfVisualTextLayout::compareLineSegments);

        var groups = new ArrayList<List<TextPosition>>();
        float lineHeight = (float) Math.max(pageMedianHeight, PdfTextPositionMetrics.MIN_LINE_HEIGHT);
        float blockGap = lineHeight * BLOCK_GAP_FACTOR;
        var current = new ArrayList<TextPosition>();
        PdfLineSegment lastLine = null;
        for (var line : lineSegments) {
            if (startsNewGroup(current, line, lastLine, lineHeight, blockGap, separators)) {
                addLastGroup(groups, current);
                current = new ArrayList<>();
            }
            current.addAll(line.positions);
            lastLine = line;
        }
        addLastGroup(groups, current);
        return groups;
    }

    static String renderGroup(List<TextPosition> group) {
        var lines = groupIntoVisualLines(group, PdfTextPositionMetrics.medianHeight(group));
        return lines.stream()
                .map(PdfTextPositionMetrics::sortByX)
                .map(PdfTextPositionMetrics::renderWithInferredSpaces)
                .filter(text -> !text.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("")
                .stripTrailing();
    }

    private static int compareLineSegments(PdfLineSegment left, PdfLineSegment right) {
        int column = Integer.compare(left.columnIndex, right.columnIndex);
        if (column != 0) {
            return column;
        }
        int y = Double.compare(left.baseline, right.baseline);
        if (y != 0) {
            return y;
        }
        return Double.compare(left.x0, right.x0);
    }

    private static List<PdfLineSegment> splitIntoLineSegments(List<TextPosition> positions, double medianHeight) {
        var lines = groupIntoVisualLines(positions, medianHeight);
        var out = new ArrayList<PdfLineSegment>();
        for (var line : lines) {
            var sortedLine = PdfTextPositionMetrics.sortByX(line);
            var nonBlank = sortedLine.stream().filter(p -> !PdfTextPositionMetrics.isBlank(p)).toList();
            if (nonBlank.isEmpty()) {
                continue;
            }
            double medianWidth = PdfTextPositionMetrics.medianWidth(nonBlank);
            double splitGap = Math.max(medianWidth * LINE_SEGMENT_GAP_FACTOR, medianHeight * 1.5);
            var current = new ArrayList<TextPosition>();
            TextPosition previous = null;
            for (var p : sortedLine) {
                if (PdfTextPositionMetrics.isBlank(p)) {
                    if (!current.isEmpty()) {
                        current.add(p);
                    }
                    continue;
                }
                if (previous != null && PdfTextPositionMetrics.horizontalGap(previous, p) > splitGap && !current.isEmpty()) {
                    out.add(PdfLineSegment.from(current));
                    current = new ArrayList<>();
                }
                current.add(p);
                previous = p;
            }
            if (!current.isEmpty()) {
                out.add(PdfLineSegment.from(current));
            }
        }
        out.sort((left, right) -> {
            int y = Double.compare(left.baseline, right.baseline);
            if (y != 0) {
                return y;
            }
            return Double.compare(left.x0, right.x0);
        });
        return out;
    }

    private static List<List<TextPosition>> groupIntoVisualLines(List<TextPosition> positions, double medianHeight) {
        var sorted = positions.stream()
                .filter(p -> !PdfTextPositionMetrics.isBlank(p))
                .sorted((left, right) -> {
                    int y = Float.compare(left.getYDirAdj(), right.getYDirAdj());
                    if (y != 0) {
                        return y;
                    }
                    return Float.compare(left.getXDirAdj(), right.getXDirAdj());
                })
                .toList();
        var lines = new ArrayList<List<TextPosition>>();
        var current = new ArrayList<TextPosition>();
        double currentBaseline = Double.NaN;
        double epsilon = Math.max(2.0, medianHeight * 0.35);
        for (var p : sorted) {
            double baseline = p.getYDirAdj();
            if (current.isEmpty() || Math.abs(baseline - currentBaseline) <= epsilon) {
                current.add(p);
                currentBaseline = Double.isNaN(currentBaseline) ? baseline : currentBaseline;
            } else {
                lines.add(current);
                current = new ArrayList<>(List.of(p));
                currentBaseline = baseline;
            }
        }
        if (!current.isEmpty()) {
            lines.add(current);
        }
        return lines;
    }

    private static void attachInlineDateSegments(List<PdfLineSegment> lines) {
        for (var line : lines) {
            if (!line.looksLikeInlineDate(DATE_RANGE)) {
                continue;
            }
            PdfLineSegment leftPeer = null;
            for (var peer : lines) {
                if (peer == line || !sameBaseline(line, peer) || peer.x1 > line.x0) {
                    continue;
                }
                if (leftPeer == null || peer.x1 > leftPeer.x1) {
                    leftPeer = peer;
                }
            }
            if (leftPeer != null) {
                line.columnIndex = leftPeer.columnIndex;
            }
        }
    }

    private static void attachInlineFieldValueSegments(List<PdfLineSegment> lines) {
        for (var line : lines) {
            if (!line.looksLikeInlineFieldValue()) {
                continue;
            }
            PdfLineSegment leftPeer = null;
            for (var peer : lines) {
                if (peer == line || !sameBaseline(line, peer) || !peer.looksLikeInlineFieldLabel()) {
                    continue;
                }
                double gap = line.x0 - peer.x1;
                if (gap < 0.0 || gap > Math.max(180.0, line.width() * 8.0)) {
                    continue;
                }
                if (leftPeer == null || peer.x1 > leftPeer.x1) {
                    leftPeer = peer;
                }
            }
            if (leftPeer != null) {
                line.columnIndex = leftPeer.columnIndex;
            }
        }
    }

    private static List<PdfColumnBand> inferColumns(List<PdfLineSegment> lines, double medianHeight) {
        var columns = new ArrayList<PdfColumnBand>();
        var byX = new ArrayList<>(lines);
        byX.sort((left, right) -> Double.compare(left.x0, right.x0));
        for (var line : byX) {
            int index = columnIndexFor(line, columns, medianHeight);
            if (index < 0) {
                columns.add(new PdfColumnBand(line.x0, line.x1));
            } else {
                columns.get(index).include(line);
            }
        }
        columns.sort((left, right) -> Double.compare(left.x0, right.x0));
        return columns;
    }

    private static int columnIndexFor(PdfLineSegment line, List<PdfColumnBand> columns, double medianHeight) {
        double bestScore = Double.POSITIVE_INFINITY;
        int bestIndex = -1;
        for (int i = 0; i < columns.size(); i++) {
            var column = columns.get(i);
            if (!sameColumn(line, column, medianHeight)) {
                continue;
            }
            double score = Math.abs(line.x0 - column.x0);
            if (score < bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static boolean sameColumn(PdfLineSegment line, PdfColumnBand column, double medianHeight) {
        double overlap = Math.max(0.0, Math.min(line.x1, column.x1) - Math.max(line.x0, column.x0));
        double minWidth = Math.max(1.0, Math.min(line.width(), column.width()));
        if (overlap / minWidth >= SAME_COLUMN_OVERLAP_RATIO) {
            return true;
        }
        return Math.abs(line.x0 - column.x0) <= Math.max(medianHeight * COLUMN_PROXIMITY_FACTOR, 72.0);
    }

    private static boolean startsNewGroup(
            List<TextPosition> current, PdfLineSegment line, PdfLineSegment lastLine, float lineHeight, float blockGap,
            List<PdfPageGraphicsExtractor.HorizontalSeparator> separators) {
        if (current.isEmpty() || lastLine == null) {
            return false;
        }
        if (line.columnIndex != lastLine.columnIndex) {
            return true;
        }
        double baselineGap = line.baseline - lastLine.baseline;
        if (baselineGap > blockGap || baselineGap < -lineHeight * 0.5f) {
            return true;
        }
        if (hasSeparatorBetween(lastLine, line, separators)) {
            return true;
        }
        if (line.isResumeSectionHeading()) {
            return true;
        }
        if (line.startsNumberedListItem() && !lastLine.isResumeSectionHeading()) {
            return true;
        }
        return line.isBoldResponsibilityHeading();
    }

    private static boolean hasSeparatorBetween(
            PdfLineSegment upper, PdfLineSegment lower, List<PdfPageGraphicsExtractor.HorizontalSeparator> separators) {
        double y0 = Math.min(upper.baseline, lower.baseline);
        double y1 = Math.max(upper.baseline, lower.baseline);
        double x0 = Math.min(upper.x0, lower.x0);
        double x1 = Math.max(upper.x1, lower.x1);
        return separators.stream().anyMatch(separator ->
                separator.y() > y0
                        && separator.y() < y1
                        && Math.min(separator.x1(), x1) - Math.max(separator.x0(), x0) > 24.0);
    }

    private static void addLastGroup(List<List<TextPosition>> groups, List<TextPosition> current) {
        if (current.isEmpty()) {
            return;
        }
        var stripped = stripTrailingBlanks(current);
        if (!stripped.isEmpty()) {
            groups.add(stripped);
        }
    }

    private static List<TextPosition> stripTrailingBlanks(List<TextPosition> group) {
        int end = group.size();
        while (end > 0 && PdfTextPositionMetrics.isBlank(group.get(end - 1))) {
            end--;
        }
        return end == group.size() ? group : new ArrayList<>(group.subList(0, end));
    }

    private static boolean sameBaseline(PdfLineSegment left, PdfLineSegment right) {
        return Math.abs(left.baseline - right.baseline) <= BASELINE_EPSILON;
    }
}
