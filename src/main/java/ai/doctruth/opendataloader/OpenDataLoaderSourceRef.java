package ai.doctruth.opendataloader;

import java.util.Objects;
import java.util.Optional;

import ai.doctruth.BoundingBox;

/** Source-map entry for OpenDataLoader-shaped projections. */
public record OpenDataLoaderSourceRef(String unitId, int pageIndex, Optional<BoundingBox> bbox, String text) {

    public OpenDataLoaderSourceRef {
        Objects.requireNonNull(unitId, "unitId");
        Objects.requireNonNull(bbox, "bbox");
        Objects.requireNonNull(text, "text");
        if (unitId.isBlank()) {
            throw new IllegalArgumentException("unitId must not be blank");
        }
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be >= 0");
        }
    }
}
