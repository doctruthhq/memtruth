package ai.doctruth;

import java.util.Objects;
import java.util.Optional;

record DiscardedBlock(int page, String reason, String text, Optional<BoundingBox> boundingBox) {

    DiscardedBlock {
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        reason = requireNonBlank(reason, "reason");
        text = requireNonBlank(text, "text");
        Objects.requireNonNull(boundingBox, "boundingBox");
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
