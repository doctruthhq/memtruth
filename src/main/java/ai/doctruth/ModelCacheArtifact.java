package ai.doctruth;

import java.util.Objects;

/**
 * Verification result for one local model artifact.
 *
 * @since 1.0.0
 */
public record ModelCacheArtifact(
        ModelDescriptor descriptor, ModelCacheStatus status, long actualSizeBytes, String actualSha256) {

    public ModelCacheArtifact {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(actualSha256, "actualSha256");
        if (actualSizeBytes < 0) {
            throw new IllegalArgumentException("actualSizeBytes must be >= 0");
        }
    }
}
