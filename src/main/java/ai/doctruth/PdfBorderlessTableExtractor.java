package ai.doctruth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.pdfbox.text.TextPosition;

final class PdfBorderlessTableExtractor {

    private static final double BASELINE_EPSILON = 2.0;
    private static final double COLUMN_ALIGNMENT_EPSILON = 8.0;
    private static final int MAX_CELL_CHARS = 32;
    private static final Pattern NUMERIC_CELL = Pattern.compile(
            "^[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[Ee][+-]?\\d+)?%?$");

    private PdfBorderlessTableExtractor() {
        throw new AssertionError("no instances");
    }

    static List<PdfPageTableExtractor.TableBlock> detect(
            List<TextPosition> positions, int pageNumber, double pageWidth, double pageHeight) {
        var rows = borderlessRows(positions);
        var anchors = columnAnchors(rows);
        if (!looksLikeAlignedTable(rows, anchors)) {
            return List.of();
        }
        var values = rows.stream().map(row -> cellTexts(row, anchors)).toList();
        var allPositions = rows.stream()
                .flatMap(row -> row.cells().stream())
                .flatMap(cell -> cell.positions().stream())
                .toList();
        var box = PdfTextPositionBoxes.layoutBox(allPositions, pageWidth, pageHeight);
        if (box.isEmpty()) {
            return List.of();
        }
        var section = new TableSection(
                values,
                new SourceLocation(pageNumber, pageNumber, 1, values.size(), 0),
                box,
                cellRegions(rows, anchors, pageWidth, pageHeight));
        return List.of(new PdfPageTableExtractor.TableBlock(section, box.orElseThrow()));
    }

    private static List<BorderlessRow> borderlessRows(List<TextPosition> positions) {
        return groupByBaseline(positions).stream()
                .map(PdfBorderlessTableExtractor::borderlessRow)
                .filter(row -> row.cells().size() >= 2)
                .toList();
    }

    private static BorderlessRow borderlessRow(List<TextPosition> line) {
        var sorted = PdfTextPositionMetrics.sortByX(line);
        var cells = new ArrayList<BorderlessCell>();
        var current = new ArrayList<TextPosition>();
        TextPosition previous = null;
        double splitGap = Math.max(24.0, PdfTextPositionMetrics.medianWidth(sorted) * 6.0);
        for (var position : sorted) {
            if (previous != null
                    && PdfTextPositionMetrics.horizontalGap(previous, position) > splitGap
                    && !current.isEmpty()) {
                cells.add(borderlessCell(current));
                current = new ArrayList<>();
            }
            current.add(position);
            previous = position;
        }
        if (!current.isEmpty()) {
            cells.add(borderlessCell(current));
        }
        return new BorderlessRow(cells);
    }

    private static boolean looksLikeAlignedTable(List<BorderlessRow> rows, List<Double> anchors) {
        if (rows.size() < 2 || anchors.size() < 2 || hasLongDataCell(rows) || hasBoldDataCell(rows)) {
            return false;
        }
        if (!rows.stream().allMatch(row -> alignedWithAnchors(row, anchors))) {
            return false;
        }
        return !hasLongHeaderCell(rows) || hasNumericDataRows(rows);
    }

    private static List<Double> columnAnchors(List<BorderlessRow> rows) {
        var sorted = rows.stream()
                .flatMap(row -> row.cells().stream())
                .map(BorderlessCell::x0)
                .sorted()
                .toList();
        var anchors = new ArrayList<Double>();
        var cluster = new ArrayList<Double>();
        for (double x : sorted) {
            if (cluster.isEmpty() || Math.abs(x - average(cluster)) <= COLUMN_ALIGNMENT_EPSILON) {
                cluster.add(x);
            } else {
                anchors.add(average(cluster));
                cluster = new ArrayList<>(List.of(x));
            }
        }
        if (!cluster.isEmpty()) {
            anchors.add(average(cluster));
        }
        return List.copyOf(anchors);
    }

    private static boolean hasLongDataCell(List<BorderlessRow> rows) {
        return dataRows(rows).stream()
                .flatMap(row -> row.cells().stream())
                .map(BorderlessCell::text)
                .anyMatch(text -> text.length() > MAX_CELL_CHARS);
    }

    private static boolean hasBoldDataCell(List<BorderlessRow> rows) {
        return dataRows(rows).stream()
                .flatMap(row -> row.cells().stream())
                .flatMap(cell -> cell.positions().stream())
                .anyMatch(PdfTextPositionMetrics::isBold);
    }

    private static boolean hasLongHeaderCell(List<BorderlessRow> rows) {
        return !rows.isEmpty() && rows.getFirst().cells().stream().map(BorderlessCell::text)
                .anyMatch(text -> text.length() > MAX_CELL_CHARS);
    }

    private static boolean hasNumericDataRows(List<BorderlessRow> rows) {
        return dataRows(rows).stream().anyMatch(PdfBorderlessTableExtractor::isNumericHeavyRow);
    }

    private static boolean isNumericHeavyRow(BorderlessRow row) {
        long numeric = row.cells().stream().map(BorderlessCell::text).filter(PdfBorderlessTableExtractor::isNumericCell).count();
        return numeric >= 2 && numeric * 2 >= row.cells().size();
    }

    private static boolean isNumericCell(String text) {
        return NUMERIC_CELL.matcher(text.strip()).matches();
    }

    private static List<BorderlessRow> dataRows(List<BorderlessRow> rows) {
        return rows.size() <= 1 ? List.of() : rows.subList(1, rows.size());
    }

    private static boolean alignedWithAnchors(BorderlessRow row, List<Double> anchors) {
        for (var cell : row.cells()) {
            if (nearestAnchor(cell, anchors) < 0) {
                return false;
            }
        }
        return true;
    }

    private static List<String> cellTexts(BorderlessRow row, List<Double> anchors) {
        var columnPositions = new ArrayList<List<TextPosition>>();
        for (int i = 0; i < anchors.size(); i++) {
            columnPositions.add(new ArrayList<>());
        }
        for (var position : row.positions()) {
            int column = anchorColumn(position.getXDirAdj(), anchors);
            if (column >= 0) {
                columnPositions.get(column).add(position);
            }
        }
        return columnPositions.stream()
                .map(PdfTextPositionMetrics::sortByX)
                .map(PdfTextPositionMetrics::renderWithInferredSpaces)
                .toList();
    }

    private static List<TableCellRegion> cellRegions(
            List<BorderlessRow> rows, List<Double> anchors, double pageWidth, double pageHeight) {
        var regions = new ArrayList<TableCellRegion>();
        for (int row = 0; row < rows.size(); row++) {
            addRowRegions(regions, row, rows.get(row).cells(), anchors, pageWidth, pageHeight);
        }
        return List.copyOf(regions);
    }

    private static void addRowRegions(
            List<TableCellRegion> regions,
            int row,
            List<BorderlessCell> cells,
            List<Double> anchors,
            double pageWidth,
            double pageHeight) {
        for (var cell : cells) {
            int column = nearestAnchor(cell, anchors);
            if (column < 0) {
                continue;
            }
            PdfTextPositionBoxes.layoutBox(cell.positions(), pageWidth, pageHeight)
                    .map(box -> new TableCellRegion(row, column, box))
                    .ifPresent(regions::add);
        }
    }

    private static int nearestAnchor(BorderlessCell cell, List<Double> anchors) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int column = 0; column < anchors.size(); column++) {
            double distance = Math.abs(cell.x0() - anchors.get(column));
            if (distance < bestDistance) {
                best = column;
                bestDistance = distance;
            }
        }
        return bestDistance <= COLUMN_ALIGNMENT_EPSILON ? best : -1;
    }

    private static int anchorColumn(double x, List<Double> anchors) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int column = 0; column < anchors.size(); column++) {
            double distance = Math.abs(x - anchors.get(column));
            if (distance < bestDistance) {
                best = column;
                bestDistance = distance;
            }
        }
        if (best > 0 && x < midpoint(anchors.get(best - 1), anchors.get(best))) {
            return best - 1;
        }
        if (best + 1 < anchors.size() && x > midpoint(anchors.get(best), anchors.get(best + 1))) {
            return best + 1;
        }
        return best;
    }

    private static double midpoint(double left, double right) {
        return left + (right - left) / 2.0;
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static List<List<TextPosition>> groupByBaseline(List<TextPosition> positions) {
        var lines = new ArrayList<List<TextPosition>>();
        var current = new ArrayList<TextPosition>();
        double baseline = Double.NaN;
        for (var position : nonBlankTopDown(positions)) {
            if (!current.isEmpty() && Math.abs(position.getYDirAdj() - baseline) > BASELINE_EPSILON) {
                lines.add(current);
                current = new ArrayList<>();
                baseline = Double.NaN;
            }
            current.add(position);
            baseline = Double.isNaN(baseline) ? position.getYDirAdj() : baseline;
        }
        if (!current.isEmpty()) {
            lines.add(current);
        }
        return List.copyOf(lines);
    }

    private static List<TextPosition> nonBlankTopDown(List<TextPosition> positions) {
        return positions.stream()
                .filter(position -> !PdfTextPositionMetrics.isBlank(position))
                .sorted(Comparator.comparingDouble(TextPosition::getYDirAdj)
                        .thenComparingDouble(TextPosition::getXDirAdj))
                .toList();
    }

    private static BorderlessCell borderlessCell(List<TextPosition> positions) {
        return new BorderlessCell(
                PdfTextPositionMetrics.renderWithInferredSpaces(PdfTextPositionMetrics.sortByX(positions)),
                positions.stream().mapToDouble(TextPosition::getXDirAdj).min().orElse(0.0),
                List.copyOf(positions));
    }

    private record BorderlessRow(List<BorderlessCell> cells) {

        List<TextPosition> positions() {
            return cells.stream().flatMap(cell -> cell.positions().stream()).toList();
        }
    }

    private record BorderlessCell(String text, double x0, List<TextPosition> positions) {}
}
