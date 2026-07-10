package ai.doctruth;

/**
 * Page-normalized rectangular evidence region using a top-left origin and a 1000-unit page
 * scale. A value of {@code x1 == 1000} means the right edge of the rendered page, regardless
 * of the source PDF page size.
 *
 * @param x0 left edge, inclusive.
 * @param y0 top edge, inclusive.
 * @param x1 right edge, exclusive.
 * @param y1 bottom edge, exclusive.
 * @since 0.2.0
 */
public record BoundingBox(double x0, double y0, double x1, double y1) {

    private static final double PAGE_MIN = 0.0;
    private static final double PAGE_MAX = 1000.0;

    public BoundingBox {
        requireFinite("x0", x0);
        requireFinite("y0", y0);
        requireFinite("x1", x1);
        requireFinite("y1", y1);
        if (x0 < PAGE_MIN || y0 < PAGE_MIN || x1 > PAGE_MAX || y1 > PAGE_MAX) {
            throw new IllegalArgumentException("bounding box must be page-normalized to 0..1000");
        }
        if (x1 <= x0 || y1 <= y0) {
            throw new IllegalArgumentException("bounding box must have positive width and height");
        }
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
