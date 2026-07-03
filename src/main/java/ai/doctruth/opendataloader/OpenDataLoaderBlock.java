package ai.doctruth.opendataloader;

import java.util.Objects;
import java.util.Optional;

import ai.doctruth.BoundingBox;

/** OpenDataLoader-shaped block projection derived from a DocTruth TrustUnit. */
public final class OpenDataLoaderBlock {

    private final String id;
    private final String kind;
    private final int pageIndex;
    private final Optional<BoundingBox> bbox;
    private final int readingOrder;
    private final String text;
    private final String sourceUnitId;

    public OpenDataLoaderBlock(
            String id,
            String kind,
            int pageIndex,
            Optional<BoundingBox> bbox,
            int readingOrder,
            String text,
            String sourceUnitId) {
        this.id = requireText(id, "id");
        this.kind = requireText(kind, "kind");
        this.pageIndex = requireNonNegative(pageIndex, "pageIndex");
        this.bbox = Objects.requireNonNull(bbox, "bbox");
        this.readingOrder = requireNonNegative(readingOrder, "readingOrder");
        this.text = Objects.requireNonNull(text, "text");
        this.sourceUnitId = requireText(sourceUnitId, "sourceUnitId");
    }

    public String id() {
        return id;
    }

    public String kind() {
        return kind;
    }

    public int pageIndex() {
        return pageIndex;
    }

    public Optional<BoundingBox> bbox() {
        return bbox;
    }

    public int readingOrder() {
        return readingOrder;
    }

    public String text() {
        return text;
    }

    public String sourceUnitId() {
        return sourceUnitId;
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
}
