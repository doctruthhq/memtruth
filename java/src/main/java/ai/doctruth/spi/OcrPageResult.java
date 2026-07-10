package ai.doctruth.spi;

import java.util.List;
import java.util.Objects;

/**
 * Output of one {@link OcrEngine#ocr(java.awt.image.BufferedImage, int) ocr} call.
 *
 * <p>Carries the recovered text plus per-region bounding boxes — these enable
 * {@link ai.doctruth.Citation Citation} objects to reference a precise pixel rectangle on
 * the source PDF page, not just a line range. That is the audit-depth differentiator over
 * libraries that only OCR to a flat text blob.
 *
 * <p>Invariants (compact constructor):
 *
 * <ul>
 *   <li>{@code text} non-null (empty string allowed — flag for genuinely blank pages).
 *   <li>{@code confidence} in {@code [0.0, 1.0]} — typically OCR engine's averaged
 *       per-character or per-word confidence. NaN / infinities rejected.
 *   <li>{@code regions} non-null and defensively copied; empty list is allowed.
 *   <li>{@code pageNumber >= 1}.
 * </ul>
 *
 * @param text       the full recovered text for the page, in reading order.
 * @param confidence average confidence over the page in {@code [0.0, 1.0]}.
 * @param regions    optional per-region bounding boxes; richer engines emit one per word /
 *                   line. Empty list is acceptable for simple engines that only emit
 *                   page-level text.
 * @param pageNumber 1-indexed page number this result corresponds to.
 * @since 0.1.0
 */
public record OcrPageResult(String text, double confidence, List<OcrRegion> regions, int pageNumber) {

    public OcrPageResult {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(regions, "regions");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be a real number in [0.0, 1.0], got " + confidence);
        }
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be >= 1, got " + pageNumber);
        }
        regions = List.copyOf(regions);
    }

    /** Empty result for blank or non-OCR-able pages. Uses confidence 0.0. */
    public static OcrPageResult empty(int pageNumber) {
        return new OcrPageResult("", 0.0, List.of(), pageNumber);
    }
}
