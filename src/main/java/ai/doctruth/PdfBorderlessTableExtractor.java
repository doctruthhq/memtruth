package ai.doctruth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.text.TextPosition;

final class PdfBorderlessTableExtractor {

    private static final double BASELINE_EPSILON = 2.0;
    private static final double COLUMN_ALIGNMENT_EPSILON = 8.0;
    private static final int MAX_CELL_CHARS = 32;

    private PdfBorderlessTableExtractor() {
        throw new AssertionError("no instances");
    }

    static List<PdfPageTableExtractor.TableBlock> detect(
            List<TextPosition> positions, int pageNumber, double pageWidth, double pageHeight) {
        var rows = borderlessRows(positions);
        if (!looksLikeAlignedTable(rows)) {
            return List.of();
        }
        int columns = rows.getFirst().cells().size();
        var values = rows.stream().map(row -> cellTexts(row, columns)).toList();
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
                cellRegions(rows, pageWidth, pageHeight));
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

    private static boolean looksLikeAlignedTable(List<BorderlessRow> rows) {
        if (rows.size() < 2 || !sameColumnCount(rows) || hasLongCell(rows) || hasBoldCell(rows)) {
            return false;
        }
        var anchors = rows.getFirst().cells().stream().map(BorderlessCell::x0).toList();
        return rows.stream().allMatch(row -> alignedWithAnchors(row, anchors));
    }

    private static boolean sameColumnCount(List<BorderlessRow> rows) {
        int columns = rows.getFirst().cells().size();
        return rows.stream().allMatch(row -> row.cells().size() == columns);
    }

    private static boolean hasLongCell(List<BorderlessRow> rows) {
        return rows.stream()
                .flatMap(row -> row.cells().stream())
                .map(BorderlessCell::text)
                .anyMatch(text -> text.length() > MAX_CELL_CHARS);
    }

    private static boolean hasBoldCell(List<BorderlessRow> rows) {
        return rows.stream()
                .flatMap(row -> row.cells().stream())
                .flatMap(cell -> cell.positions().stream())
                .anyMatch(PdfTextPositionMetrics::isBold);
    }

    private static boolean alignedWithAnchors(BorderlessRow row, List<Double> anchors) {
        for (int i = 0; i < anchors.size(); i++) {
            if (Math.abs(row.cells().get(i).x0() - anchors.get(i)) > COLUMN_ALIGNMENT_EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static List<String> cellTexts(BorderlessRow row, int columns) {
        return row.cells().subList(0, columns).stream().map(BorderlessCell::text).toList();
    }

    private static List<TableCellRegion> cellRegions(List<BorderlessRow> rows, double pageWidth, double pageHeight) {
        var regions = new ArrayList<TableCellRegion>();
        for (int row = 0; row < rows.size(); row++) {
            addRowRegions(regions, row, rows.get(row).cells(), pageWidth, pageHeight);
        }
        return List.copyOf(regions);
    }

    private static void addRowRegions(
            List<TableCellRegion> regions, int row, List<BorderlessCell> cells, double pageWidth, double pageHeight) {
        for (int column = 0; column < cells.size(); column++) {
            int columnIndex = column;
            PdfTextPositionBoxes.layoutBox(cells.get(column).positions(), pageWidth, pageHeight)
                    .map(box -> new TableCellRegion(row, columnIndex, box))
                    .ifPresent(regions::add);
        }
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

    private record BorderlessRow(List<BorderlessCell> cells) {}

    private record BorderlessCell(String text, double x0, List<TextPosition> positions) {}
}
