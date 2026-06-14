package ai.doctruth;

import java.util.List;
import java.util.Objects;

/**
 * Parser/runtime provenance for a {@link TrustDocument}.
 *
 * @param parserRunId   stable id for this parser run.
 * @param parserVersion DocTruth parser contract/runtime version.
 * @param preset        parser preset such as lite or standard.
 * @param backend       backend identity such as pdfbox or rust-sidecar.
 * @param models        model identifiers used by the run.
 * @param warnings      structured parser warnings emitted by the run.
 * @since 1.0.0
 */
public record ParserRun(
        String parserRunId,
        String parserVersion,
        String preset,
        String backend,
        List<String> models,
        List<ParserWarning> warnings) {

    private static final String DEFAULT_PARSER_RUN_ID = "parser-run-0001";

    public ParserRun(
            String parserVersion, String preset, String backend, List<String> models, List<ParserWarning> warnings) {
        this(DEFAULT_PARSER_RUN_ID, parserVersion, preset, backend, models, warnings);
    }

    public ParserRun {
        Objects.requireNonNull(parserRunId, "parserRunId");
        Objects.requireNonNull(parserVersion, "parserVersion");
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(warnings, "warnings");
        requireNotBlank("parserRunId", parserRunId);
        requireNotBlank("parserVersion", parserVersion);
        requireNotBlank("preset", preset);
        requireNotBlank("backend", backend);
        models = copyNonBlankStrings("models", models);
        warnings = List.copyOf(warnings);
    }

    private static void requireNotBlank(String name, String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
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
}
