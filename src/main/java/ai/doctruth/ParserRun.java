package ai.doctruth;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parser/runtime provenance for a {@link TrustDocument}.
 *
 * @param parserRunId   stable id for this parser run.
 * @param parserVersion DocTruth parser contract/runtime version.
 * @param preset        parser preset such as lite or standard.
 * @param backend       backend identity such as pdfbox or rust-sidecar.
 * @param details       extended parser details such as models, warnings, and oracle metrics.
 * @since 1.0.0
 */
public record ParserRun(
        String parserRunId,
        String parserVersion,
        String preset,
        String backend,
        ParserRunDetails details) {

    private static final String DEFAULT_PARSER_RUN_ID = "parser-run-0001";

    public ParserRun(
            String parserVersion, String preset, String backend, List<String> models, List<ParserWarning> warnings) {
        this(DEFAULT_PARSER_RUN_ID, parserVersion, preset, backend, models, warnings);
    }

    public ParserRun(
            String parserRunId,
            String parserVersion,
            String preset,
            String backend,
            List<String> models,
            List<ParserWarning> warnings) {
        this(parserRunId, parserVersion, preset, backend, new ParserRunDetails(models, warnings));
    }

    public ParserRun(
            String parserRunId,
            String parserVersion,
            String preset,
            String backend,
            List<String> models,
            List<ParserWarning> warnings,
            Map<String, String> externalBackend,
            Long elapsedMs) {
        this(
                parserRunId,
                parserVersion,
                preset,
                backend,
                new ParserRunDetails(models, warnings, externalBackend, elapsedMs));
    }

    public ParserRun {
        Objects.requireNonNull(parserRunId, "parserRunId");
        Objects.requireNonNull(parserVersion, "parserVersion");
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(details, "details");
        requireNotBlank("parserRunId", parserRunId);
        requireNotBlank("parserVersion", parserVersion);
        requireNotBlank("preset", preset);
        requireNotBlank("backend", backend);
    }

    public List<String> models() {
        return details.models();
    }

    public List<ParserWarning> warnings() {
        return details.warnings();
    }

    public Map<String, String> externalBackend() {
        return details.externalBackend();
    }

    public Long elapsedMs() {
        return details.elapsedMs();
    }

    private static void requireNotBlank(String name, String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
