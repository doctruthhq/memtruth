package ai.doctruth;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Human-review label metadata for one parser benchmark case.
 *
 * @since 1.0.0
 */
public record ParserBenchmarkLabel(
        Optional<String> labelId,
        List<String> tags,
        Optional<String> sourceSha256,
        List<String> fixtureTypes,
        List<String> behaviors) {

    public static final ParserBenchmarkLabel NONE =
            new ParserBenchmarkLabel(Optional.empty(), List.of(), Optional.empty(), List.of(), List.of());

    public ParserBenchmarkLabel(Optional<String> labelId, List<String> tags) {
        this(labelId, tags, Optional.empty(), List.of(), List.of());
    }

    public ParserBenchmarkLabel(Optional<String> labelId, List<String> tags, Optional<String> sourceSha256) {
        this(labelId, tags, sourceSha256, List.of(), List.of());
    }

    public ParserBenchmarkLabel {
        Objects.requireNonNull(labelId, "labelId");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(sourceSha256, "sourceSha256");
        Objects.requireNonNull(fixtureTypes, "fixtureTypes");
        Objects.requireNonNull(behaviors, "behaviors");
        tags = List.copyOf(tags);
        fixtureTypes = List.copyOf(fixtureTypes);
        behaviors = List.copyOf(behaviors);
    }
}
