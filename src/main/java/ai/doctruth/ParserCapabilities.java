package ai.doctruth;

import java.util.List;
import java.util.Objects;

/**
 * Static capabilities for a parser backend.
 *
 * @param backend         backend identity.
 * @param supportsPdf     true when PDF input is supported.
 * @param supportsModels  true when backend can run model-assisted parsing.
 * @param networkRequired true when backend requires network access.
 * @param outputProfiles  supported output profiles.
 * @since 1.0.0
 */
public record ParserCapabilities(
        String backend, boolean supportsPdf, boolean supportsModels, boolean networkRequired, List<String> outputProfiles) {

    public ParserCapabilities {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(outputProfiles, "outputProfiles");
        if (backend.isBlank()) {
            throw new IllegalArgumentException("backend must not be blank");
        }
        outputProfiles = copyNonBlankStrings(outputProfiles);
    }

    private static List<String> copyNonBlankStrings(List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            var value = Objects.requireNonNull(values.get(i), "outputProfiles[" + i + "]");
            if (value.isBlank()) {
                throw new IllegalArgumentException("outputProfiles must not contain blank values");
            }
        }
        return List.copyOf(values);
    }
}

