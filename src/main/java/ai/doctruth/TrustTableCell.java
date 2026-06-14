package ai.doctruth;

import java.util.Objects;
import java.util.Optional;

/**
 * Structured table cell available for cell-level evidence.
 *
 * @param cellId     stable cell id.
 * @param rowRange   row span.
 * @param columnRange column span.
 * @param boundingBox optional cell bounding box.
 * @param text       recovered cell text.
 * @since 1.0.0
 */
public record TrustTableCell(
        String cellId, TrustCellRange rowRange, TrustCellRange columnRange, Optional<BoundingBox> boundingBox, String text) {

    public TrustTableCell {
        Objects.requireNonNull(cellId, "cellId");
        Objects.requireNonNull(rowRange, "rowRange");
        Objects.requireNonNull(columnRange, "columnRange");
        Objects.requireNonNull(boundingBox, "boundingBox");
        Objects.requireNonNull(text, "text");
        if (cellId.isBlank()) {
            throw new IllegalArgumentException("cellId must not be blank");
        }
    }
}

