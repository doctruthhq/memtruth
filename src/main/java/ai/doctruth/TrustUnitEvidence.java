package ai.doctruth;

import java.util.Objects;
import java.util.Optional;

/**
 * Source anchor for one TrustDocument unit.
 *
 * @param location    page and line anchor.
 * @param boundingBox optional page-normalized visual evidence region.
 * @param blockKind   layout class for text units, empty otherwise.
 * @since 0.2.0
 */
public record TrustUnitEvidence(
        SourceLocation location, Optional<BoundingBox> boundingBox, Optional<BlockKind> blockKind) {

    public TrustUnitEvidence {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(boundingBox, "boundingBox");
        Objects.requireNonNull(blockKind, "blockKind");
    }
}
