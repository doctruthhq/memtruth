package ai.doctruth.spi;

import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Optional OCR backend, plugged into {@code PdfDocumentParser} to recover text from scanned
 * (image-only) pages. The OSS library ships ONLY this interface plus the {@link #NOOP}
 * default — no real OCR engine is bundled (per AGENTS.md "What this project is NOT —
 * NOT an OCR library"). Real implementations are owned by:
 *
 * <ul>
 *   <li>The {@code doctruth-enterprise} commercial jar — {@code TesseractOcrEngine},
 *       {@code LlmVisionOcrEngine}, {@code TextractOcrEngine}.
 *   <li>Community contributions — anything implementing this single-method interface.
 * </ul>
 *
 * <p>This is the fourth SPI in the open-core split (per ADR 0006), alongside
 * {@link AuditEventListener}, {@link SignatureProvider}, and {@code LlmProvider.region()}.
 *
 * <p>Threading: implementations MUST be thread-safe — the parser may invoke {@code ocr}
 * concurrently across pages on virtual threads.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface OcrEngine {

    /**
     * OCR a single rendered page image.
     *
     * @param pageImage  the page rendered to a raster image (caller responsibility — typically
     *                   {@code PDFRenderer.renderImageWithDPI(page, 150)}).
     * @param pageNumber the 1-indexed page number, surfaced for logging / region traceability.
     * @return the OCR result; never null. Implementations that cannot OCR should return
     *         {@link OcrPageResult#empty(int)} rather than throwing.
     */
    OcrPageResult ocr(BufferedImage pageImage, int pageNumber);

    /**
     * No-op engine — returns empty text on every page. The OSS default; means
     * {@code PdfDocumentParser} treats scanned pages as zero-content (matches the v0.1.0-alpha
     * behaviour before this SPI shipped). Callers wanting real OCR plug in a richer impl.
     */
    OcrEngine NOOP = (pageImage, pageNumber) -> {
        Objects.requireNonNull(pageImage, "pageImage");
        return OcrPageResult.empty(pageNumber);
    };
}
