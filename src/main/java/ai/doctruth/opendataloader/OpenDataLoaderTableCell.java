package ai.doctruth.opendataloader;

import java.util.Objects;
import java.util.Optional;

import ai.doctruth.BoundingBox;

/** OpenDataLoader-shaped table cell projection. */
public final class OpenDataLoaderTableCell {

    private final String id;
    private final int rowStart;
    private final int rowEnd;
    private final int columnStart;
    private final int columnEnd;
    private final Optional<BoundingBox> bbox;
    private final String text;

    public OpenDataLoaderTableCell(
            String id,
            int rowStart,
            int rowEnd,
            int columnStart,
            int columnEnd,
            Optional<BoundingBox> bbox,
            String text) {
        this.id = requireText(id, "id");
        this.rowStart = requireNonNegative(rowStart, "rowStart");
        this.rowEnd = requireAtLeast(rowEnd, rowStart, "rowEnd");
        this.columnStart = requireNonNegative(columnStart, "columnStart");
        this.columnEnd = requireAtLeast(columnEnd, columnStart, "columnEnd");
        this.bbox = Objects.requireNonNull(bbox, "bbox");
        this.text = Objects.requireNonNull(text, "text");
    }

    public String id() {
        return id;
    }

    public int rowStart() {
        return rowStart;
    }

    public int rowEnd() {
        return rowEnd;
    }

    public int columnStart() {
        return columnStart;
    }

    public int columnEnd() {
        return columnEnd;
    }

    public Optional<BoundingBox> bbox() {
        return bbox;
    }

    public String text() {
        return text;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
        return value;
    }

    private static int requireAtLeast(int value, int min, String name) {
        if (value < min) {
            throw new IllegalArgumentException(name + " must be >= start");
        }
        return value;
    }
}
