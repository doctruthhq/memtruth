package ai.doctruth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.pdfbox.text.TextPosition;

final class PdfBorderlessTableExtractor {

    private static final double BASELINE_EPSILON = 2.0;
    private static final double COLUMN_ALIGNMENT_EPSILON = 8.0;
    private static final double HEADER_ALIGNMENT_EPSILON = 72.0;
    private static final double MAX_HEADER_BAND_GAP = 120.0;
    private static final double MAX_TABLE_ROW_GAP = 42.0;
    private static final int MAX_HEADER_ROWS = 8;
    private static final int MAX_CELL_CHARS = 32;
    private static final Pattern NUMERIC_CELL = Pattern.compile(
            "^[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[Ee][+-]?\\d+)?%?$");

    private PdfBorderlessTableExtractor() {
        throw new AssertionError("no instances");
    }

    static List<PdfPageTableExtractor.TableBlock> detect(
            List<TextPosition> positions, int pageNumber, double pageWidth, double pageHeight) {
        var allRows = allRows(positions);
        var rows = allRows.stream().filter(row -> row.cells().size() >= 2).toList();
        var tables = new ArrayList<PdfPageTableExtractor.TableBlock>();
        for (var run : tableRuns(rows)) {
            var anchors = columnAnchors(run);
            var rowsWithHeader = prependStackedHeaderRow(allRows, run, anchors);
            tableBlock(rowsWithHeader, anchors, pageNumber, pageWidth, pageHeight).ifPresent(tables::add);
        }
        return List.copyOf(tables);
    }

    private static List<BorderlessRow> allRows(List<TextPosition> positions) {
        return groupByBaseline(positions).stream()
                .map(PdfBorderlessTableExtractor::borderlessRow)
                .filter(row -> !row.text().isBlank())
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

    private static List<List<BorderlessRow>> tableRuns(List<BorderlessRow> rows) {
        var runs = new ArrayList<List<BorderlessRow>>();
        var current = new ArrayList<BorderlessRow>();
        for (var row : rows) {
            if (!current.isEmpty() && breaksTableRun(current, row)) {
                addCandidateRun(runs, current);
                current = new ArrayList<>();
            }
            current.add(row);
        }
        addCandidateRun(runs, current);
        return List.copyOf(runs);
    }

    private static void addCandidateRun(List<List<BorderlessRow>> runs, List<BorderlessRow> rows) {
        if (rows.size() >= 2) {
            runs.add(List.copyOf(rows));
        }
    }

    private static boolean breaksTableRun(List<BorderlessRow> current, BorderlessRow next) {
        if (verticalGap(current.getLast(), next) > MAX_TABLE_ROW_GAP) {
            return true;
        }
        if (current.size() < 2) {
            return false;
        }
        var anchors = columnAnchors(current);
        return anchors.size() >= 2 && !alignedWithAnchors(next, anchors);
    }

    private static double verticalGap(BorderlessRow previous, BorderlessRow next) {
        return Math.max(0.0, next.y0() - previous.y1());
    }

    private static List<BorderlessRow> prependStackedHeaderRow(
            List<BorderlessRow> allRows, List<BorderlessRow> run, List<Double> anchors) {
        var header = stackedHeaderRow(allRows, run, anchors);
        if (header.isEmpty()) {
            return run;
        }
        var out = new ArrayList<BorderlessRow>(run.size() + 1);
        out.add(header.orElseThrow());
        out.addAll(run);
        return List.copyOf(out);
    }

    private static Optional<BorderlessRow> stackedHeaderRow(
            List<BorderlessRow> allRows, List<BorderlessRow> run, List<Double> anchors) {
        int firstRunRow = allRows.indexOf(run.getFirst());
        if (firstRunRow <= 0 || anchors.size() < 2) {
            return Optional.empty();
        }
        var headerRows = new ArrayList<BorderlessRow>();
        for (int index = firstRunRow - 1; index >= 0 && headerRows.size() < MAX_HEADER_ROWS; index--) {
            var candidate = allRows.get(index);
            if (looksLikeTableCaption(candidate.text()) || headerBandGap(candidate, run.getFirst()) > MAX_HEADER_BAND_GAP) {
                break;
            }
            if (looksLikeHeaderRow(candidate) && hasHeaderAlignedCell(candidate, anchors)) {
                headerRows.add(0, candidate);
            }
        }
        return syntheticHeaderRow(headerRows, anchors);
    }

    private static double headerBandGap(BorderlessRow headerCandidate, BorderlessRow firstTableRow) {
        return Math.max(0.0, firstTableRow.y0() - headerCandidate.y1());
    }

    private static boolean looksLikeHeaderRow(BorderlessRow row) {
        if (row.cells().size() >= 2) {
            return true;
        }
        if (looksLikeAllCapsHeading(row.text())) {
            return false;
        }
        return row.text().matches("(?i).*(category|clauses?|percent|laws?|small|medium|large|year|rate|basis|expense|depreciation).*");
    }

    private static boolean looksLikeAllCapsHeading(String text) {
        var stripped = text.strip();
        return stripped.length() > 20
                && stripped.equals(stripped.toUpperCase(java.util.Locale.ROOT))
                && stripped.chars().filter(Character::isLetter).count() >= 10;
    }

    private static boolean hasHeaderAlignedCell(BorderlessRow row, List<Double> anchors) {
        return row.cells().stream().anyMatch(cell -> nearestHeaderColumn(cell, anchors) >= 0);
    }

    private static boolean looksLikeTableCaption(String text) {
        var stripped = text.strip();
        return stripped.matches("(?i)^table\\s+\\d+[:.].*")
                || stripped.matches("^\\d+$")
                || stripped.length() > 64;
    }

    private static Optional<BorderlessRow> syntheticHeaderRow(List<BorderlessRow> headerRows, List<Double> anchors) {
        if (headerRows.isEmpty()) {
            return Optional.empty();
        }
        var columns = new ArrayList<StringBuilder>();
        var positionsByColumn = new ArrayList<List<TextPosition>>();
        for (int column = 0; column < anchors.size(); column++) {
            columns.add(new StringBuilder());
            positionsByColumn.add(new ArrayList<>());
        }
        for (var row : headerRows) {
            for (var cell : row.cells()) {
                int column = nearestHeaderColumn(cell, anchors);
                if (column >= 0) {
                    appendCell(columns.get(column), cell.text());
                    positionsByColumn.get(column).addAll(cell.positions());
                }
            }
        }
        if (columns.stream().anyMatch(column -> column.toString().isBlank())) {
            return Optional.empty();
        }
        var cells = new ArrayList<BorderlessCell>();
        for (int column = 0; column < anchors.size(); column++) {
            cells.add(new BorderlessCell(columns.get(column).toString(), anchors.get(column), positionsByColumn.get(column)));
        }
        return Optional.of(new BorderlessRow(cells));
    }

    private static Optional<PdfPageTableExtractor.TableBlock> tableBlock(
            List<BorderlessRow> rows, List<Double> anchors, int pageNumber, double pageWidth, double pageHeight) {
        if (!looksLikeAlignedTable(rows, anchors)) {
            return Optional.empty();
        }
        var values = rows.stream().map(row -> cellTexts(row, anchors)).toList();
        var allPositions = rows.stream()
                .flatMap(row -> row.cells().stream())
                .flatMap(cell -> cell.positions().stream())
                .toList();
        var box = PdfTextPositionBoxes.layoutBox(allPositions, pageWidth, pageHeight);
        if (box.isEmpty()) {
            return Optional.empty();
        }
        var section = new TableSection(
                values,
                new SourceLocation(pageNumber, pageNumber, 1, values.size(), 0),
                box,
                cellRegions(rows, anchors, pageWidth, pageHeight));
        return Optional.of(new PdfPageTableExtractor.TableBlock(section, box.orElseThrow()));
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
        var columns = new ArrayList<StringBuilder>();
        for (int i = 0; i < anchors.size(); i++) {
            columns.add(new StringBuilder());
        }
        for (var cell : row.cells()) {
            int column = nearestAnchor(cell, anchors);
            if (column >= 0) {
                appendCell(columns.get(column), cell.text());
            }
        }
        return columns.stream().map(StringBuilder::toString).toList();
    }

    private static void appendCell(StringBuilder column, String text) {
        var stripped = text.strip();
        if (stripped.isEmpty()) {
            return;
        }
        if (!column.isEmpty()) {
            column.append(' ');
        }
        column.append(stripped);
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
        return nearestColumn(cell, anchors, COLUMN_ALIGNMENT_EPSILON);
    }

    private static int nearestHeaderColumn(BorderlessCell cell, List<Double> anchors) {
        return nearestColumn(cell, anchors, HEADER_ALIGNMENT_EPSILON);
    }

    private static int nearestColumn(BorderlessCell cell, List<Double> anchors, double epsilon) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int column = 0; column < anchors.size(); column++) {
            double distance = Math.abs(cell.x0() - anchors.get(column));
            if (distance < bestDistance) {
                best = column;
                bestDistance = distance;
            }
        }
        return bestDistance <= epsilon ? best : -1;
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

        String text() {
            return cells.stream().map(BorderlessCell::text).reduce("", PdfBorderlessTableExtractor::joinText);
        }

        double y0() {
            return positions().stream().mapToDouble(TextPosition::getYDirAdj).min().orElse(0.0);
        }

        double y1() {
            return positions().stream()
                    .mapToDouble(position -> position.getYDirAdj() + position.getHeightDir())
                    .max()
                    .orElse(0.0);
        }
    }

    private record BorderlessCell(String text, double x0, List<TextPosition> positions) {}

    private static String joinText(String left, String right) {
        if (left.isBlank()) {
            return right.strip();
        }
        if (right.isBlank()) {
            return left.strip();
        }
        return left.strip() + " " + right.strip();
    }
}
