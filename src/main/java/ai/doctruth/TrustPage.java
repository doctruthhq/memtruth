package ai.doctruth;

import java.util.Objects;

/**
 * Page anchor in a {@link TrustDocument}.
 *
 * @param pageNumber         1-indexed page number.
 * @param width              rendered page width in normalized units or pixels.
 * @param height             rendered page height in normalized units or pixels.
 * @param textLayerAvailable whether a native text layer exists.
 * @param imageHash          optional page image hash, blank when unavailable.
 * @since 1.0.0
 */
public record TrustPage(int pageNumber, double width, double height, boolean textLayerAvailable, String imageHash) {

    public TrustPage {
        Objects.requireNonNull(imageHash, "imageHash");
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be >= 1");
        }
        if (!Double.isFinite(width) || width <= 0) {
            throw new IllegalArgumentException("width must be positive and finite");
        }
        if (!Double.isFinite(height) || height <= 0) {
            throw new IllegalArgumentException("height must be positive and finite");
        }
    }
}
