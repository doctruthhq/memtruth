package ai.doctruth;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Extended parser runtime details carried by {@link ParserRun}.
 *
 * @param models          model identifiers used by the run.
 * @param warnings        structured parser warnings emitted by the run.
 * @param externalBackend benchmark/oracle backend provenance, when used.
 * @param elapsedMs       parser elapsed time in milliseconds, when measured.
 * @since 1.0.0
 */
public record ParserRunDetails(
        List<String> models, List<ParserWarning> warnings, Map<String, String> externalBackend, Long elapsedMs) {

    public ParserRunDetails(List<String> models, List<ParserWarning> warnings) {
        this(models, warnings, Map.of(), null);
    }

    public ParserRunDetails {
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(warnings, "warnings");
        Objects.requireNonNull(externalBackend, "externalBackend");
        models = copyNonBlankStrings("models", models);
        warnings = List.copyOf(warnings);
        externalBackend = copyNonBlankMap("externalBackend", externalBackend);
        if (elapsedMs != null && elapsedMs < 0) {
            throw new IllegalArgumentException("elapsedMs must be >= 0");
        }
    }

    private static List<String> copyNonBlankStrings(String name, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            var value = Objects.requireNonNull(values.get(i), name + "[" + i + "]");
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain blank values");
            }
        }
        return List.copyOf(values);
    }

    private static Map<String, String> copyNonBlankMap(String name, Map<String, String> values) {
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, name + " key");
            Objects.requireNonNull(value, name + "[" + key + "]");
            if (key.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain blank keys");
            }
        });
        return Map.copyOf(values);
    }
}
