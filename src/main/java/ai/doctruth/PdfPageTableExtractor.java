package ai.doctruth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;

final class PdfPageTableExtractor {

    private static final double LINE_CLUSTER_EPSILON = 2.0;

    private PdfPageTableExtractor() {
        throw new AssertionError("no instances");
    }

    static List<TableSection> detectTablesOnPage(PDDocument pdf, int pageNumber) throws IOException {
        return detectTableBlocksOnPage(pdf, pageNumber).stream().map(TableBlock::section).toList();
    }

    static List<TableBlock> detectTableBlocksOnPage(PDDocument pdf, int pageNumber) throws IOException {
        var positions = PdfPageBlockExtractor.capturePageTextPositions(pdf, pageNumber);
        if (positions.isEmpty()) {
            return List.of();
        }
        var page = pdf.getPage(pageNumber - 1).getMediaBox();
        var lines = PdfPageGraphicsExtractor.extractGridLines(pdf.getPage(pageNumber - 1));
        var xs = clustered(lines.vertical().stream().map(PdfPageGraphicsExtractor.VerticalSeparator::x).toList());
        var ys = clustered(lines.horizontal().stream().map(PdfPageGraphicsExtractor.HorizontalSeparator::y).toList());
        if (xs.size() < 2 || ys.size() < 2 || !looksLikeTableGrid(lines, xs, ys)) {
            return PdfBorderlessTableExtractor.detect(positions, pageNumber, page.getWidth(), page.getHeight());
        }
        var cells = detectedCells(lines, xs, ys);
        var rows = rowsFromGrid(positions, cells, ys.size() - 1, xs.size() - 1);
        if (!hasNonBlankCell(rows)) {
            return List.of();
        }
        var box = normalizedBox(xs, ys, page.getWidth(), page.getHeight());
        var section = new TableSection(
                rows,
                new SourceLocation(pageNumber, pageNumber, 1, rows.size(), 0),
                Optional.of(box),
                cellRegions(cells, pageNumber, page.getWidth(), page.getHeight()));
        return List.of(new TableBlock(section, box));
    }

    record TableBlock(TableSection section, BoundingBox boundingBox) {

        boolean contains(PdfTextBlock block) {
            return block.boundingBox().filter(this::contains).isPresent();
        }

        private boolean contains(BoundingBox box) {
            double x = (box.x0() + box.x1()) / 2.0;
            double y = (box.y0() + box.y1()) / 2.0;
            return x >= boundingBox.x0()
                    && x <= boundingBox.x1()
                    && y >= boundingBox.y0()
                    && y <= boundingBox.y1();
        }
    }

    private static boolean looksLikeTableGrid(
            PdfPageGraphicsExtractor.GridLines lines, List<Double> xs, List<Double> ys) {
        double left = xs.getFirst();
        double right = xs.getLast();
        double top = ys.getFirst();
        double bottom = ys.getLast();
        long spanningHorizontal = lines.horizontal().stream()
                .filter(line -> line.x0() <= left + LINE_CLUSTER_EPSILON)
                .filter(line -> line.x1() >= right - LINE_CLUSTER_EPSILON)
                .count();
        return spanningHorizontal >= 2
                && verticalBoundaryCovers(lines, left, top, bottom)
                && verticalBoundaryCovers(lines, right, top, bottom);
    }

    private static List<DetectedCell> detectedCells(
            PdfPageGraphicsExtractor.GridLines lines, List<Double> xs, List<Double> ys) {
        var out = new ArrayList<DetectedCell>();
        var occupied = new boolean[ys.size() - 1][xs.size() - 1];
        for (int row = 0; row < ys.size() - 1; row++) {
            double y0 = ys.get(row);
            double y1 = ys.get(row + 1);
            for (int column = 0; column < xs.size() - 1; column++) {
                if (occupied[row][column]) {
                    continue;
                }
                int endColumn = mergedColumnEnd(lines, xs, y0, y1, column);
                int endRow = mergedRowEnd(lines, xs, ys, row, column, endColumn);
                out.add(new DetectedCell(
                        new CellGridRange(row, column, endRow, endColumn),
                        new RawCellBox(xs.get(column), xs.get(endColumn + 1), y0, ys.get(endRow + 1))));
                markOccupied(occupied, row, column, endRow, endColumn);
                column = endColumn;
            }
        }
        return List.copyOf(out);
    }

    private static int mergedColumnEnd(
            PdfPageGraphicsExtractor.GridLines lines, List<Double> xs, double y0, double y1, int column) {
        int end = column;
        while (end < xs.size() - 2 && !verticalBoundaryCovers(lines, xs.get(end + 1), y0, y1)) {
            end++;
        }
        return end;
    }

    private static int mergedRowEnd(
            PdfPageGraphicsExtractor.GridLines lines, List<Double> xs, List<Double> ys, int row, int column, int endColumn) {
        int end = row;
        double x0 = xs.get(column);
        double x1 = xs.get(endColumn + 1);
        while (end < ys.size() - 2 && !horizontalBoundaryCovers(lines, ys.get(end + 1), x0, x1)) {
            end++;
        }
        return end;
    }

    private static void markOccupied(boolean[][] occupied, int row, int column, int rowEnd, int columnEnd) {
        for (int y = row; y <= rowEnd; y++) {
            for (int x = column; x <= columnEnd; x++) {
                occupied[y][x] = true;
            }
        }
    }

    private static boolean horizontalBoundaryCovers(
            PdfPageGraphicsExtractor.GridLines lines, double y, double x0, double x1) {
        return lines.horizontal().stream()
                .filter(line -> Math.abs(line.y() - y) <= LINE_CLUSTER_EPSILON)
                .anyMatch(line -> line.x0() <= x0 + LINE_CLUSTER_EPSILON
                        && line.x1() >= x1 - LINE_CLUSTER_EPSILON);
    }

    private static boolean verticalBoundaryCovers(
            PdfPageGraphicsExtractor.GridLines lines, double x, double y0, double y1) {
        return lines.vertical().stream()
                .filter(line -> Math.abs(line.x() - x) <= LINE_CLUSTER_EPSILON)
                .anyMatch(line -> line.y0() <= y0 + LINE_CLUSTER_EPSILON
                        && line.y1() >= y1 - LINE_CLUSTER_EPSILON);
    }

    private static List<List<String>> rowsFromGrid(
            List<TextPosition> positions, List<DetectedCell> detectedCells, int rowCount, int columnCount) {
        var rows = new ArrayList<List<String>>(rowCount);
        for (int row = 0; row < rowCount; row++) {
            var cells = new ArrayList<String>(java.util.Collections.nCopies(columnCount, ""));
            for (var cell : detectedCells) {
                if (cell.range().row() == row) {
                    cells.set(cell.range().column(), cellText(positions, cell.box().x0(), cell.box().x1(), cell.box().y0(), cell.box().y1()));
                }
            }
            rows.add(List.copyOf(cells));
        }
        return List.copyOf(rows);
    }

    private static String cellText(List<TextPosition> positions, double x0, double x1, double y0, double y1) {
        var cellPositions = positions.stream()
                .filter(position -> !PdfTextPositionMetrics.isBlank(position))
                .filter(position -> inside(position, x0, x1, y0, y1))
                .sorted((left, right) -> {
                    int y = Float.compare(left.getYDirAdj(), right.getYDirAdj());
                    return y != 0 ? y : Float.compare(left.getXDirAdj(), right.getXDirAdj());
                })
                .toList();
        return PdfVisualTextLayout.renderGroup(cellPositions);
    }

    private static boolean inside(TextPosition position, double x0, double x1, double y0, double y1) {
        double x = position.getXDirAdj() + position.getWidthDirAdj() / 2.0;
        double y = position.getYDirAdj();
        return x > x0 + LINE_CLUSTER_EPSILON
                && x < x1 - LINE_CLUSTER_EPSILON
                && y > y0 + LINE_CLUSTER_EPSILON
                && y < y1 - LINE_CLUSTER_EPSILON;
    }

    private static boolean hasNonBlankCell(List<List<String>> rows) {
        return rows.stream().flatMap(List::stream).anyMatch(cell -> !cell.isBlank());
    }

    private static BoundingBox normalizedBox(List<Double> xs, List<Double> ys, double pageWidth, double pageHeight) {
        return new BoundingBox(
                clamp(xs.getFirst() * 1000.0 / pageWidth),
                clamp(ys.getFirst() * 1000.0 / pageHeight),
                clamp(xs.getLast() * 1000.0 / pageWidth),
                clamp(ys.getLast() * 1000.0 / pageHeight));
    }

    private static List<TableCellRegion> cellRegions(
            List<DetectedCell> detectedCells, int pageNumber, double pageWidth, double pageHeight) {
        var regions = new ArrayList<TableCellRegion>(detectedCells.size());
        for (var cell : detectedCells) {
            normalizedCellBox(cell.box(), pageWidth, pageHeight).ifPresent(box -> regions.add(new TableCellRegion(
                    pageNumber,
                    cell.range().row(),
                    cell.range().column(),
                    cell.range().rowEnd(),
                    cell.range().columnEnd(),
                    box)));
        }
        return List.copyOf(regions);
    }

    private static Optional<BoundingBox> normalizedCellBox(RawCellBox box, double pageWidth, double pageHeight) {
        double x0 = clamp(box.x0() * 1000.0 / pageWidth);
        double y0 = clamp(box.y0() * 1000.0 / pageHeight);
        double x1 = clamp(box.x1() * 1000.0 / pageWidth);
        double y1 = clamp(box.y1() * 1000.0 / pageHeight);
        if (x1 <= x0 || y1 <= y0) {
            return Optional.empty();
        }
        return Optional.of(new BoundingBox(x0, y0, x1, y1));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1000.0, value));
    }

    private static List<Double> clustered(List<Double> values) {
        if (values.isEmpty()) {
            return List.of();
        }
        var sorted = values.stream().sorted().toList();
        var out = new ArrayList<Double>();
        var cluster = new ArrayList<Double>();
        for (double value : sorted) {
            if (cluster.isEmpty() || Math.abs(value - average(cluster)) <= LINE_CLUSTER_EPSILON) {
                cluster.add(value);
            } else {
                out.add(average(cluster));
                cluster = new ArrayList<>(List.of(value));
            }
        }
        out.add(average(cluster));
        return List.copyOf(out);
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private record DetectedCell(CellGridRange range, RawCellBox box) {}

    private record CellGridRange(int row, int column, int rowEnd, int columnEnd) {}

    private record RawCellBox(double x0, double x1, double y0, double y1) {}
}
