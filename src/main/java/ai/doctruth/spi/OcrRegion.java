package ai.doctruth.spi;

import java.util.Objects;

/**
 * One OCR-recovered text region with its pixel bounding box on the rendered page image.
 * The {@code (x, y, width, height)} are pixels at the rendering DPI; downstream code
 * scales to PDF user-space if needed via the same DPI.
 *
 * <p>Why pixel coordinates and not PDF user-space points? OCR engines work on raster
 * images; reporting the pixel box back is the only honest representation of where
 * the engine "saw" the text. Callers that need PDF user-space coordinates convert
 * once, with the DPI they used.
 *
 * <p>Invariants (constructors):
 *
 * <ul>
 *   <li>{@code text} non-null (empty allowed — represents a region the engine couldn't
 *       transcribe).
 *   <li>{@code box} non-null and geometrically valid.
 *   <li>{@code confidence} in {@code [0.0, 1.0]}.
 * </ul>
 *
 * @param text       the recovered text in this region.
 * @param box        pixel bounding box on the rendered page image.
 * @param confidence per-region confidence in {@code [0.0, 1.0]}.
 * @since 0.1.0
 */
public record OcrRegion(String text, OcrBox box, double confidence) {

    public OcrRegion(String text, int x, int y, int width, int height, double confidence) {
        this(text, new OcrBox(x, y, width, height), confidence);
    }

    public OcrRegion {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(box, "box");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be a real number in [0.0, 1.0], got " + confidence);
        }
    }

    public int x() {
        return box.x();
    }

    public int y() {
        return box.y();
    }

    public int width() {
        return box.width();
    }

    public int height() {
        return box.height();
    }
}
