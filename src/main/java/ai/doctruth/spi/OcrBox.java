package ai.doctruth.spi;

/**
 * Pixel bounding box for one OCR region on a rendered page image.
 *
 * @param x      pixel x of the top-left corner.
 * @param y      pixel y of the top-left corner.
 * @param width  pixel width.
 * @param height pixel height.
 * @since 0.1.0
 */
public record OcrBox(int x, int y, int width, int height) {

    public OcrBox {
        if (x < 0) throw new IllegalArgumentException("x must be >= 0, got " + x);
        if (y < 0) throw new IllegalArgumentException("y must be >= 0, got " + y);
        if (width <= 0) throw new IllegalArgumentException("width must be > 0, got " + width);
        if (height <= 0) throw new IllegalArgumentException("height must be > 0, got " + height);
    }
}
