package ai.doctruth;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured table region in a {@link TrustDocument}.
 *
 * @param tableId    stable table id.
 * @param pageNumber 1-indexed page number.
 * @param boundingBox optional table bounding box.
 * @param confidence table recognition confidence.
 * @param cells      structured cells.
 * @since 1.0.0
 */
public record TrustTable(
        String tableId,
        int pageNumber,
        Optional<BoundingBox> boundingBox,
        Confidence confidence,
        List<TrustTableCell> cells) {

    public TrustTable {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(boundingBox, "boundingBox");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(cells, "cells");
        if (tableId.isBlank()) {
            throw new IllegalArgumentException("tableId must not be blank");
        }
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be >= 1");
        }
        cells = List.copyOf(cells);
    }
}
