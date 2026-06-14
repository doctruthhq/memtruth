package ai.doctruth;

import java.util.List;
import java.util.Objects;

/**
 * Aggregated local model cache verification report.
 *
 * @since 1.0.0
 */
public record ModelCacheReport(List<ModelCacheArtifact> artifacts, List<ParserWarning> warnings) {

    public ModelCacheReport {
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(warnings, "warnings");
        artifacts = List.copyOf(artifacts);
        warnings = List.copyOf(warnings);
    }

    public boolean allReady() {
        return artifacts.stream().allMatch(artifact -> artifact.status() == ModelCacheStatus.READY);
    }

    public long totalSizeBytes() {
        return artifacts.stream().mapToLong(ModelCacheArtifact::actualSizeBytes).sum();
    }
}
