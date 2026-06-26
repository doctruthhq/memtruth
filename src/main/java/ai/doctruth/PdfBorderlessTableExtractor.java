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
        var wideTextTable = wideTextTable(allRows, pageNumber, pageWidth, pageHeight);
        if (!wideTextTable.isEmpty()) {
            return wideTextTable;
        }
        var rows = allRows.stream().filter(row -> row.cells().size() >= 2).toList();
        var tables = new ArrayList<PdfPageTableExtractor.TableBlock>();
        for (var run : tableRuns(rows)) {
            var anchors = columnAnchors(run);
            var rowsWithContinuations = addContinuationRows(allRows, run, anchors);
            var rowsWithHeader = prependStackedHeaderRow(allRows, rowsWithContinuations, anchors);
            tableBlock(rowsWithHeader, anchors, pageNumber, pageWidth, pageHeight).ifPresent(tables::add);
        }
        if (!tables.isEmpty()) {
            return List.copyOf(tables);
        }
        return columnStreamNumericTable(allRows, pageNumber, pageWidth, pageHeight);
    }

    private static List<PdfPageTableExtractor.TableBlock> wideTextTable(
            List<BorderlessRow> allRows, int pageNumber, double pageWidth, double pageHeight) {
        var rows = wideTextTableRows(allRows);
        if (rows.size() < 3) {
            return List.of();
        }
        var anchors = columnAnchors(rows.stream().filter(row -> row.cells().size() >= 3).toList());
        if (anchors.size() < 4 || !looksLikeWideTextTable(rows, anchors)) {
            return List.of();
        }
        return wideTextTableBlock(rows, anchors, pageNumber, pageWidth, pageHeight)
                .map(List::of)
                .orElseGet(List::of);
    }

    private static List<PdfPageTableExtractor.TableBlock> columnStreamNumericTable(
            List<BorderlessRow> allRows, int pageNumber, double pageWidth, double pageHeight) {
        for (int start = 0; start < allRows.size(); start++) {
            var candidate = columnStreamNumericRows(allRows, start);
            if (candidate.size() < 4) {
                continue;
            }
            var anchors = columnStreamAnchors(candidate);
            if (anchors.size() < 4 || columnStreamDataRows(candidate, anchors) < 3) {
                continue;
            }
            return columnStreamNumericTableBlock(candidate, anchors, pageNumber, pageWidth, pageHeight)
                    .map(List::of)
                    .orElseGet(List::of);
        }
        return List.of();
    }

    private static List<Double> columnStreamAnchors(List<BorderlessRow> rows) {
        var dataRows = rows.stream()
                .filter(row -> row.cells().size() >= 3)
                .filter(PdfBorderlessTableExtractor::isNumericHeavyRow)
                .toList();
        return dataRows.isEmpty() ? List.of() : columnAnchors(dataRows);
    }

    private static List<BorderlessRow> columnStreamNumericRows(List<BorderlessRow> allRows, int start) {
        if (!looksLikeColumnStreamHeaderStart(allRows.get(start))) {
            return List.of();
        }
        var out = new ArrayList<BorderlessRow>();
        out.add(allRows.get(start));
        boolean seenData = false;
        for (int index = start + 1; index < allRows.size(); index++) {
            var row = allRows.get(index);
            if (looksLikeTableCaption(row.text()) || looksLikeSourceLine(row.text())) {
                break;
            }
            if (!out.isEmpty() && verticalGap(out.getLast(), row) > MAX_TABLE_ROW_GAP) {
                break;
            }
            if (row.cells().size() >= 2 || looksLikeFirstColumnContinuation(row)) {
                out.add(row);
                seenData = seenData || isNumericHeavyRow(row);
                continue;
            }
            if (seenData) {
                break;
            }
        }
        return seenData ? List.copyOf(out) : List.of();
    }

    private static boolean looksLikeColumnStreamHeaderStart(BorderlessRow row) {
        if (row.cells().size() < 3 || isNumericHeavyRow(row)) {
            return false;
        }
        long textCells = row.cells().stream()
                .map(BorderlessCell::text)
                .filter(text -> !text.isBlank())
                .filter(text -> !isNumericCell(text))
                .count();
        return textCells >= 2;
    }

    private static boolean looksLikeFirstColumnContinuation(BorderlessRow row) {
        return row.cells().size() == 1
                && !row.text().isBlank()
                && !looksLikeAllCapsHeading(row.text())
                && !looksLikeSourceLine(row.text());
    }

    private static boolean looksLikeSourceLine(String text) {
        return text.strip().matches("(?i)^(source|note|notes)\\b.*");
    }

    private static long columnStreamDataRows(List<BorderlessRow> rows, List<Double> anchors) {
        return rows.stream()
                .map(row -> cellTexts(row, anchors))
                .filter(PdfBorderlessTableExtractor::isNumericHeavyValues)
                .count();
    }

    private static boolean isNumericHeavyValues(List<String> row) {
        long numeric = row.stream().filter(PdfBorderlessTableExtractor::isNumericCell).count();
        return numeric >= 2 && numeric * 2 >= row.size();
    }

    private static Optional<PdfPageTableExtractor.TableBlock> columnStreamNumericTableBlock(
            List<BorderlessRow> rows, List<Double> anchors, int pageNumber, double pageWidth, double pageHeight) {
        var values = columnStreamNumericValues(rows, anchors);
        if (values.size() < 3 || values.getFirst().stream().filter(cell -> !cell.isBlank()).count() < 2) {
            return Optional.empty();
        }
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

    private static List<List<String>> columnStreamNumericValues(List<BorderlessRow> rows, List<Double> anchors) {
        var nearest = rows.stream().map(row -> cellTexts(row, anchors)).toList();
        int firstData = firstNumericDataRow(nearest);
        if (firstData <= 0) {
            return nearest;
        }
        var out = new ArrayList<List<String>>();
        var headerRows = rows.subList(0, firstData).stream()
                .map(row -> zonedCellTexts(row, anchors))
                .toList();
        out.add(mergedHeader(headerRows, anchors.size()));
        out.addAll(mergeContinuationRows(nearest.subList(firstData, nearest.size())));
        return normalizeSpacerColumns(out);
    }

    private static int firstNumericDataRow(List<List<String>> rows) {
        for (int index = 0; index < rows.size(); index++) {
            if (isNumericHeavyValues(rows.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> mergedHeader(List<List<String>> rows, int columns) {
        var out = new ArrayList<String>();
        for (int column = 0; column < columns; column++) {
            var text = new StringBuilder();
            for (var row : rows) {
                if (column < row.size()) {
                    appendCell(text, row.get(column));
                }
            }
            out.add(text.toString());
        }
        return List.copyOf(out);
    }

    private static List<BorderlessRow> wideTextTableRows(List<BorderlessRow> allRows) {
        int start = -1;
        int end = -1;
        for (int index = 0; index < allRows.size(); index++) {
            var row = allRows.get(index);
            if (start < 0 && row.cells().size() >= 4 && row.text().matches("(?i).*\\b(jurisdiction|country|category)\\b.*")) {
                start = index;
            } else if (start >= 0 && looksLikePageFooter(row.text())) {
                end = index;
                break;
            }
        }
        if (start < 0) {
            return List.of();
        }
        int tableEnd = end < 0 ? allRows.size() : end;
        return List.copyOf(allRows.subList(start, tableEnd));
    }

    private static boolean looksLikePageFooter(String text) {
        return text.matches("(?i).*\\b(page|library|copyright)\\b.*\\d+\\s*$");
    }

    private static boolean looksLikeWideTextTable(List<BorderlessRow> rows, List<Double> anchors) {
        long multiColumnRows = rows.stream().filter(row -> row.cells().size() >= 3).count();
        long dataStarts = rows.stream()
                .filter(row -> row.cells().size() >= 4)
                .filter(PdfBorderlessTableExtractor::looksLikeWideTextDataStart)
                .count();
        return multiColumnRows >= 3 && dataStarts >= 2 && rows.stream().allMatch(row -> wideRowFits(row, anchors));
    }

    private static boolean looksLikeWideTextDataStart(BorderlessRow row) {
        var first = row.cells().getFirst().text().strip();
        return !first.isBlank()
                && !first.matches("(?i).*(jurisdiction|country|category|year|gats|foreign|ownership|reservation).*")
                && row.cells().stream().skip(1).filter(cell -> !cell.text().isBlank()).count() >= 2;
    }

    private static boolean wideRowFits(BorderlessRow row, List<Double> anchors) {
        return row.cells().stream().allMatch(cell -> nearestHeaderColumn(cell, anchors) >= 0);
    }

    private static Optional<PdfPageTableExtractor.TableBlock> wideTextTableBlock(
            List<BorderlessRow> rows, List<Double> anchors, int pageNumber, double pageWidth, double pageHeight) {
        var values = normalizeSpacerColumns(mergeWideContinuationRows(mergeLeadingHeaderRows(
                rows.stream().map(row -> zonedCellTexts(row, anchors)).toList())));
        if (values.size() < 2 || values.getFirst().stream().filter(cell -> !cell.isBlank()).count() < 2) {
            return Optional.empty();
        }
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

    private static List<List<String>> mergeLeadingHeaderRows(List<List<String>> rows) {
        int firstData = firstWideTextDataRow(rows);
        if (firstData <= 1) {
            return rows;
        }
        var header = new ArrayList<String>();
        for (int column = 0; column < rows.getFirst().size(); column++) {
            var text = new StringBuilder();
            for (int row = 0; row < firstData; row++) {
                appendCell(text, rows.get(row).get(column));
            }
            header.add(cleanHeaderText(text.toString()));
        }
        var out = new ArrayList<List<String>>();
        out.add(List.copyOf(header));
        out.addAll(rows.subList(firstData, rows.size()));
        return List.copyOf(out);
    }

    private static int firstWideTextDataRow(List<List<String>> rows) {
        for (int row = 0; row < rows.size(); row++) {
            if (looksLikeWideTextDataRow(rows.get(row))) {
                return row;
            }
        }
        return -1;
    }

    private static boolean looksLikeWideTextDataRow(List<String> row) {
        var first = row.getFirst().strip();
        return row.size() >= 4
                && !first.isBlank()
                && !first.matches("(?i).*(jurisdiction|country|category|year|gats|foreign|ownership|reservation).*")
                && row.stream().skip(1).filter(cell -> !cell.isBlank()).count() >= 2;
    }

    private static List<List<String>> mergeWideContinuationRows(List<List<String>> rows) {
        var out = new ArrayList<List<String>>();
        for (var row : rows) {
            if (isBlankFirstContinuation(row) && !out.isEmpty()) {
                out.set(out.size() - 1, appendContinuationCells(out.getLast(), row));
            } else {
                out.add(row);
            }
        }
        return List.copyOf(out);
    }

    private static boolean isBlankFirstContinuation(List<String> row) {
        return !row.isEmpty() && row.getFirst().isBlank() && row.stream().skip(1).anyMatch(cell -> !cell.isBlank());
    }

    private static List<String> appendContinuationCells(List<String> previous, List<String> continuation) {
        var out = new ArrayList<String>();
        int columns = Math.max(previous.size(), continuation.size());
        for (int column = 0; column < columns; column++) {
            var left = column < previous.size() ? previous.get(column) : "";
            var right = column < continuation.size() ? continuation.get(column) : "";
            out.add(appendText(left, right));
        }
        return List.copyOf(out);
    }

    private static String cleanHeaderText(String text) {
        return text.strip()
                .replace("Foreign Ownership Ownership", "Foreign Ownership")
                .replace("GATS XVII Reservation Ownership (1994)", "GATS XVII Reservation (1994)");
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
        double splitGap = Math.max(8.0, PdfTextPositionMetrics.medianWidth(sorted) * 2.0);
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

    private static List<BorderlessRow> addContinuationRows(
            List<BorderlessRow> allRows, List<BorderlessRow> run, List<Double> anchors) {
        int first = allRows.indexOf(run.getFirst());
        int last = allRows.indexOf(run.getLast());
        if (first < 0 || last < first) {
            return run;
        }
        var out = new ArrayList<BorderlessRow>();
        for (int index = first; index <= last; index++) {
            var row = allRows.get(index);
            if (run.contains(row) || looksLikeColumnContinuation(row, anchors)) {
                out.add(row);
            }
        }
        for (int index = last + 1; index < allRows.size(); index++) {
            var row = allRows.get(index);
            if (verticalGap(out.getLast(), row) > MAX_TABLE_ROW_GAP || !looksLikeColumnContinuation(row, anchors)) {
                break;
            }
            out.add(row);
        }
        return out.size() >= run.size() ? List.copyOf(out) : run;
    }

    private static boolean looksLikeColumnContinuation(BorderlessRow row, List<Double> anchors) {
        if (row.cells().size() != 1 || looksLikeAllCapsHeading(row.text()) || looksLikeTableCaption(row.text())) {
            return false;
        }
        return nearestHeaderColumn(row.cells().getFirst(), anchors) >= 0;
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
        var values = normalizeSpacerColumns(mergeContinuationRows(rows.stream().map(row -> cellTexts(row, anchors)).toList()));
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

    private static List<List<String>> mergeContinuationRows(List<List<String>> rows) {
        var out = new ArrayList<List<String>>();
        String pendingFirstColumn = "";
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (isFirstColumnContinuation(row)) {
                if (previousRowNeedsContinuation(out)) {
                    out.set(out.size() - 1, appendFirstColumn(out.getLast(), row.getFirst()));
                } else if (nextRowNeedsFirstColumn(rows, index)) {
                    pendingFirstColumn = appendText(pendingFirstColumn, row.getFirst());
                } else if (!out.isEmpty() && rowHasData(out.getLast())) {
                    out.set(out.size() - 1, appendFirstColumn(out.getLast(), row.getFirst()));
                } else {
                    pendingFirstColumn = appendText(pendingFirstColumn, row.getFirst());
                }
                continue;
            }
            if (isSingleColumnContinuation(row)) {
                mergeSingleColumnContinuation(out, row);
                continue;
            }
            var merged = new ArrayList<>(row);
            if (!pendingFirstColumn.isBlank() && merged.getFirst().isBlank()) {
                merged.set(0, pendingFirstColumn);
                pendingFirstColumn = "";
            }
            out.add(List.copyOf(merged));
        }
        if (!pendingFirstColumn.isBlank()) {
            out.add(firstColumnOnlyRow(pendingFirstColumn, rows));
        }
        return List.copyOf(out);
    }

    private static boolean isSingleColumnContinuation(List<String> row) {
        return nonBlankColumn(row) > 0 && row.stream().filter(cell -> !cell.isBlank()).count() == 1;
    }

    private static int nonBlankColumn(List<String> row) {
        for (int column = 0; column < row.size(); column++) {
            if (!row.get(column).isBlank()) {
                return column;
            }
        }
        return -1;
    }

    private static void mergeSingleColumnContinuation(List<List<String>> out, List<String> row) {
        if (out.isEmpty()) {
            out.add(row);
            return;
        }
        int column = nonBlankColumn(row);
        if (column < 0) {
            return;
        }
        var previous = new ArrayList<>(out.getLast());
        while (previous.size() <= column) {
            previous.add("");
        }
        previous.set(column, appendText(previous.get(column), row.get(column)));
        out.set(out.size() - 1, List.copyOf(previous));
    }

    private static List<List<String>> normalizeSpacerColumns(List<List<String>> rows) {
        if (rows.size() < 2 || rows.getFirst().size() < 2) {
            return rows;
        }
        var normalized = mutableRows(rows);
        for (int column = 0; column + 1 < normalized.getFirst().size(); column++) {
            if (headerOnlyColumnBeforeDataOnlyColumn(normalized, column)) {
                normalized.getFirst().set(column + 1, normalized.getFirst().get(column));
                normalized.getFirst().set(column, "");
            } else if (dataOnlyColumnBeforeHeaderOnlyColumn(normalized, column)) {
                normalized.getFirst().set(column, normalized.getFirst().get(column + 1));
                normalized.getFirst().set(column + 1, "");
            }
        }
        return removeBlankColumns(normalized);
    }

    private static List<List<String>> mutableRows(List<List<String>> rows) {
        var out = new ArrayList<List<String>>();
        for (var row : rows) {
            out.add(new ArrayList<>(row));
        }
        return out;
    }

    private static boolean headerOnlyColumnBeforeDataOnlyColumn(List<List<String>> rows, int column) {
        return !rows.getFirst().get(column).isBlank()
                && bodyColumnBlank(rows, column)
                && rows.getFirst().get(column + 1).isBlank()
                && !bodyColumnBlank(rows, column + 1);
    }

    private static boolean dataOnlyColumnBeforeHeaderOnlyColumn(List<List<String>> rows, int column) {
        return rows.getFirst().get(column).isBlank()
                && !bodyColumnBlank(rows, column)
                && !rows.getFirst().get(column + 1).isBlank()
                && bodyColumnBlank(rows, column + 1);
    }

    private static boolean bodyColumnBlank(List<List<String>> rows, int column) {
        return rows.stream().skip(1).allMatch(row -> column >= row.size() || row.get(column).isBlank());
    }

    private static List<List<String>> removeBlankColumns(List<List<String>> rows) {
        var keep = new ArrayList<Integer>();
        int columns = rows.getFirst().size();
        for (int column = 0; column < columns; column++) {
            if (!wholeColumnBlank(rows, column)) {
                keep.add(column);
            }
        }
        return rows.stream().map(row -> keptColumns(row, keep)).toList();
    }

    private static boolean wholeColumnBlank(List<List<String>> rows, int column) {
        return rows.stream().allMatch(row -> column >= row.size() || row.get(column).isBlank());
    }

    private static List<String> keptColumns(List<String> row, List<Integer> keep) {
        var out = new ArrayList<String>();
        for (int column : keep) {
            out.add(column < row.size() ? row.get(column) : "");
        }
        return List.copyOf(out);
    }

    private static boolean previousRowNeedsContinuation(List<List<String>> out) {
        if (out.isEmpty() || !rowHasData(out.getLast())) {
            return false;
        }
        var firstColumn = out.getLast().getFirst().strip().toLowerCase(java.util.Locale.ROOT);
        return firstColumn.endsWith(" with")
                || firstColumn.endsWith(" of")
                || firstColumn.endsWith(" and")
                || firstColumn.endsWith(" than")
                || firstColumn.endsWith(" to");
    }

    private static boolean nextRowNeedsFirstColumn(List<List<String>> rows, int index) {
        if (index + 1 >= rows.size()) {
            return false;
        }
        var next = rows.get(index + 1);
        return !next.isEmpty() && next.getFirst().isBlank() && rowHasData(next);
    }

    private static boolean isFirstColumnContinuation(List<String> row) {
        if (row.isEmpty() || row.getFirst().isBlank()) {
            return false;
        }
        long nonBlank = row.stream().filter(cell -> !cell.isBlank()).count();
        return nonBlank == 1 && !isNumericCell(row.getFirst());
    }

    private static boolean rowHasData(List<String> row) {
        return row.stream().skip(1).anyMatch(cell -> !cell.isBlank());
    }

    private static List<String> appendFirstColumn(List<String> row, String continuation) {
        var out = new ArrayList<>(row);
        out.set(0, appendText(out.getFirst(), continuation));
        return List.copyOf(out);
    }

    private static String appendText(String left, String right) {
        if (left.isBlank()) {
            return right.strip();
        }
        if (right.isBlank()) {
            return left.strip();
        }
        return left.strip() + " " + right.strip();
    }

    private static List<String> firstColumnOnlyRow(String text, List<List<String>> rows) {
        int columns = rows.isEmpty() ? 1 : rows.getFirst().size();
        var out = new ArrayList<String>();
        out.add(text.strip());
        while (out.size() < columns) {
            out.add("");
        }
        return List.copyOf(out);
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
        if (hasSpanningHeaderCell(row, anchors)) {
            return zonedCellTexts(row, anchors);
        }
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

    private static boolean hasSpanningHeaderCell(BorderlessRow row, List<Double> anchors) {
        if (anchors.size() < 4 || row.cells().size() + 2 >= anchors.size()) {
            return false;
        }
        return row.cells().stream().anyMatch(cell -> spanningAnchorCount(cell, anchors) >= 3);
    }

    private static long spanningAnchorCount(BorderlessCell cell, List<Double> anchors) {
        if (cell.positions().isEmpty()) {
            return 0;
        }
        double left = cell.positions().stream().mapToDouble(TextPosition::getXDirAdj).min().orElse(cell.x0());
        double right = cell.positions().stream()
                .mapToDouble(position -> position.getXDirAdj() + position.getWidthDirAdj())
                .max()
                .orElse(cell.x0());
        return anchors.stream().filter(anchor -> anchor >= left && anchor <= right).count();
    }

    private static List<String> zonedCellTexts(BorderlessRow row, List<Double> anchors) {
        var columns = new ArrayList<List<TextPosition>>();
        for (int i = 0; i < anchors.size(); i++) {
            columns.add(new ArrayList<>());
        }
        for (var cell : row.cells()) {
            for (var word : wordGroups(cell.positions())) {
                for (var segment : splitByZoneGap(word, anchors)) {
                    int column = zoneColumn(segment, anchors);
                    if (column >= 0) {
                        columns.get(column).addAll(segment);
                    }
                }
            }
        }
        return columns.stream()
                .map(PdfTextPositionMetrics::sortByX)
                .map(PdfTextPositionMetrics::renderWithInferredSpaces)
                .map(String::strip)
                .toList();
    }

    private static List<List<TextPosition>> wordGroups(List<TextPosition> positions) {
        var sorted = PdfTextPositionMetrics.sortByX(positions);
        if (sorted.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<List<TextPosition>>();
        var current = new ArrayList<TextPosition>();
        TextPosition previous = null;
        double wordGap = Math.max(1.5, PdfTextPositionMetrics.medianWidth(sorted) * 0.75);
        for (var position : sorted) {
            if (position.getUnicode().isBlank()) {
                if (!current.isEmpty()) {
                    out.add(List.copyOf(current));
                    current = new ArrayList<>();
                }
                previous = null;
                continue;
            }
            if (previous != null && PdfTextPositionMetrics.horizontalGap(previous, position) > wordGap) {
                out.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            current.add(position);
            previous = position;
        }
        if (!current.isEmpty()) {
            out.add(List.copyOf(current));
        }
        return List.copyOf(out);
    }

    private static List<List<TextPosition>> splitByZoneGap(List<TextPosition> positions, List<Double> anchors) {
        var sorted = PdfTextPositionMetrics.sortByX(positions);
        if (sorted.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<List<TextPosition>>();
        var current = new ArrayList<TextPosition>();
        TextPosition previous = null;
        int currentZone = -1;
        for (var position : sorted) {
            int zone = zoneColumn(position, anchors);
            if (previous != null
                    && zone != currentZone
                    && currentZone >= 0
                    && PdfTextPositionMetrics.horizontalGap(previous, position) > 0.5) {
                out.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            current.add(position);
            previous = position;
            currentZone = zone;
        }
        if (!current.isEmpty()) {
            out.add(List.copyOf(current));
        }
        return List.copyOf(out);
    }

    private static int zoneColumn(TextPosition position, List<Double> anchors) {
        return zoneColumn(position.getXDirAdj(), anchors);
    }

    private static int zoneColumn(List<TextPosition> positions, List<Double> anchors) {
        double left = positions.stream().mapToDouble(TextPosition::getXDirAdj).min().orElse(0.0);
        double right = positions.stream()
                .mapToDouble(position -> position.getXDirAdj() + position.getWidthDirAdj())
                .max()
                .orElse(left);
        return zoneColumn(midpoint(left, right), anchors);
    }

    private static int zoneColumn(double x, List<Double> anchors) {
        for (int column = 0; column < anchors.size(); column++) {
            double left = column == 0 ? Double.NEGATIVE_INFINITY : midpoint(anchors.get(column - 1), anchors.get(column));
            double right = column + 1 >= anchors.size()
                    ? Double.POSITIVE_INFINITY
                    : midpoint(anchors.get(column), anchors.get(column + 1));
            if (x >= left && x < right) {
                return column;
            }
        }
        return -1;
    }

    private static double midpoint(double left, double right) {
        return left + (right - left) / 2.0;
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
