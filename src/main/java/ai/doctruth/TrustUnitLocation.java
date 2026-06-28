package ai.doctruth;

import java.util.Objects;
import java.util.Optional;

/**
 * Page and layout anchor for a {@link TrustUnit}.
 *
 * @param page         1-indexed page number.
 * @param boundingBox  optional normalized bounding box.
 * @param readingOrder stable reading-order index.
 * @since 1.0.0
 */
public record TrustUnitLocation(int page, Optional<BoundingBox> boundingBox, int readingOrder) {

    public TrustUnitLocation {
        Objects.requireNonNull(boundingBox, "boundingBox");
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (readingOrder < 0) {
            throw new IllegalArgumentException("readingOrder must be >= 0");
        }
    }
}
