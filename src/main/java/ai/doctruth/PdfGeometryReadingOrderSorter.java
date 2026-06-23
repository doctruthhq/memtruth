package ai.doctruth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class PdfGeometryReadingOrderSorter {

    private static final double MIN_GAP = 5.0;

    private PdfGeometryReadingOrderSorter() {
        throw new AssertionError("no instances");
    }

    static List<PdfLineSegment> sort(List<PdfLineSegment> lines) {
        if (lines.size() <= 1) {
            return List.copyOf(lines);
        }
        return segment(new ArrayList<>(lines));
    }

    private static List<PdfLineSegment> segment(List<PdfLineSegment> lines) {
        if (lines.size() <= 1) {
            return lines;
        }
        // Adapted from OpenDataLoader's XYCutPlusPlusSorter projection-cut structure.
        var horizontal = bestHorizontalCut(lines);
        var vertical = bestVerticalCut(lines);
        if (horizontal.gap() < MIN_GAP && vertical.gap() < MIN_GAP) {
            return sortTopLeft(lines);
        }
        return horizontal.gap() >= vertical.gap()
                ? flatten(splitHorizontal(lines, horizontal.position()))
                : flatten(splitVertical(lines, vertical.position()));
    }

    private static List<PdfLineSegment> flatten(List<List<PdfLineSegment>> groups) {
        var out = new ArrayList<PdfLineSegment>();
        for (var group : groups) {
            out.addAll(segment(group));
        }
        return out;
    }

    private static Cut bestHorizontalCut(List<PdfLineSegment> lines) {
        var sorted = sortTopLeft(lines);
        double largestGap = 0.0;
        double position = 0.0;
        Double bottom = null;
        for (var line : sorted) {
            if (bottom != null && line.y0 > bottom) {
                double gap = line.y0 - bottom;
                if (gap > largestGap) {
                    largestGap = gap;
                    position = (bottom + line.y0) / 2.0;
                }
            }
            bottom = bottom == null ? line.y1 : Math.max(bottom, line.y1);
        }
        return new Cut(position, largestGap);
    }

    private static Cut bestVerticalCut(List<PdfLineSegment> lines) {
        var sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble((PdfLineSegment line) -> line.x0)
                .thenComparingDouble(line -> line.x1));
        double largestGap = 0.0;
        double position = 0.0;
        Double right = null;
        for (var line : sorted) {
            if (right != null && line.x0 > right) {
                double gap = line.x0 - right;
                if (gap > largestGap) {
                    largestGap = gap;
                    position = (right + line.x0) / 2.0;
                }
            }
            right = right == null ? line.x1 : Math.max(right, line.x1);
        }
        return new Cut(position, largestGap);
    }

    private static List<List<PdfLineSegment>> splitHorizontal(List<PdfLineSegment> lines, double y) {
        var above = new ArrayList<PdfLineSegment>();
        var below = new ArrayList<PdfLineSegment>();
        for (var line : lines) {
            (centerY(line) < y ? above : below).add(line);
        }
        return orderedGroups(above, below);
    }

    private static List<List<PdfLineSegment>> splitVertical(List<PdfLineSegment> lines, double x) {
        var left = new ArrayList<PdfLineSegment>();
        var right = new ArrayList<PdfLineSegment>();
        for (var line : lines) {
            (centerX(line) < x ? left : right).add(line);
        }
        return orderedGroups(left, right);
    }

    private static List<List<PdfLineSegment>> orderedGroups(List<PdfLineSegment> first, List<PdfLineSegment> second) {
        var groups = new ArrayList<List<PdfLineSegment>>(2);
        if (!first.isEmpty()) {
            groups.add(first);
        }
        if (!second.isEmpty()) {
            groups.add(second);
        }
        return groups;
    }

    private static List<PdfLineSegment> sortTopLeft(List<PdfLineSegment> lines) {
        var sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble((PdfLineSegment line) -> line.y0)
                .thenComparingDouble(line -> line.x0));
        return sorted;
    }

    private static double centerX(PdfLineSegment line) {
        return (line.x0 + line.x1) / 2.0;
    }

    private static double centerY(PdfLineSegment line) {
        return (line.y0 + line.y1) / 2.0;
    }

    private record Cut(double position, double gap) {}
}
