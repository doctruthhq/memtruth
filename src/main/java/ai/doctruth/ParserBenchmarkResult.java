package ai.doctruth;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Metric output for one parser benchmark fixture.
 *
 * @since 1.0.0
 */
public record ParserBenchmarkResult(
        String name,
        Optional<String> labelId,
        List<String> tags,
        Optional<String> sourceSha256,
        Map<String, Double> metrics) {

    public ParserBenchmarkResult(String name, Map<String, Double> metrics) {
        this(name, Optional.empty(), List.of(), Optional.empty(), metrics);
    }

    public ParserBenchmarkResult {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(labelId, "labelId");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(sourceSha256, "sourceSha256");
        Objects.requireNonNull(metrics, "metrics");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        tags = List.copyOf(tags);
        metrics = Map.copyOf(metrics);
    }

    public double metric(String name) {
        Objects.requireNonNull(name, "name");
        return metrics.getOrDefault(name, 0.0);
    }
}
