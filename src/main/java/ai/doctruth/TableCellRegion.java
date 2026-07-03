package ai.doctruth;

import java.util.Objects;

/**
 * Source-region geometry for a parsed table cell.
 *
 * @param page        1-indexed source page for this cell.
 * @param rowRange    zero-based inclusive row range.
 * @param columnRange zero-based inclusive column range.
 * @param boundingBox normalized source-region box for the cell.
 * @since 0.2.0
 */
public record TableCellRegion(int page, TrustCellRange rowRange, TrustCellRange columnRange, BoundingBox boundingBox) {

    public TableCellRegion(int row, int column, BoundingBox boundingBox) {
        this(1, row, column, row, column, boundingBox);
    }

    public TableCellRegion(int row, int column, int rowEnd, int columnEnd, BoundingBox boundingBox) {
        this(1, row, column, rowEnd, columnEnd, boundingBox);
    }

    public TableCellRegion(int page, int row, int column, int rowEnd, int columnEnd, BoundingBox boundingBox) {
        this(page, rowRange(row, rowEnd), columnRange(column, columnEnd), boundingBox);
    }

    public TableCellRegion {
        Objects.requireNonNull(rowRange, "rowRange");
        Objects.requireNonNull(columnRange, "columnRange");
        Objects.requireNonNull(boundingBox, "boundingBox");
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (rowRange.start() < 0) {
            throw new IllegalArgumentException("row must be >= 0");
        }
        if (columnRange.start() < 0) {
            throw new IllegalArgumentException("column must be >= 0");
        }
    }

    public int row() {
        return rowRange.start();
    }

    public int rowEnd() {
        return rowRange.end();
    }

    public int column() {
        return columnRange.start();
    }

    public int columnEnd() {
        return columnRange.end();
    }

    private static TrustCellRange rowRange(int row, int rowEnd) {
        if (row < 0) {
            throw new IllegalArgumentException("row must be >= 0");
        }
        if (rowEnd < row) {
            throw new IllegalArgumentException("rowEnd must be >= row");
        }
        return new TrustCellRange(row, rowEnd);
    }

    private static TrustCellRange columnRange(int column, int columnEnd) {
        if (column < 0) {
            throw new IllegalArgumentException("column must be >= 0");
        }
        if (columnEnd < column) {
            throw new IllegalArgumentException("columnEnd must be >= column");
        }
        return new TrustCellRange(column, columnEnd);
    }
}
