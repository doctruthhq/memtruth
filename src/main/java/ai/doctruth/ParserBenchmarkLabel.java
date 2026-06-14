package ai.doctruth;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Human-review label metadata for one parser benchmark case.
 *
 * @since 1.0.0
 */
public record ParserBenchmarkLabel(Optional<String> labelId, List<String> tags, Optional<String> sourceSha256) {

    public static final ParserBenchmarkLabel NONE =
            new ParserBenchmarkLabel(Optional.empty(), List.of(), Optional.empty());

    public ParserBenchmarkLabel(Optional<String> labelId, List<String> tags) {
        this(labelId, tags, Optional.empty());
    }

    public ParserBenchmarkLabel {
        Objects.requireNonNull(labelId, "labelId");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(sourceSha256, "sourceSha256");
        tags = List.copyOf(tags);
    }
}
