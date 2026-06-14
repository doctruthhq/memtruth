package ai.doctruth;

import java.util.List;
import java.util.Objects;

/**
 * Runtime health for a parser backend.
 *
 * @param backend   backend identity.
 * @param available true when backend can run locally.
 * @param warnings  health warnings.
 * @since 1.0.0
 */
public record ParserHealth(String backend, boolean available, List<ParserWarning> warnings) {

    public ParserHealth {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(warnings, "warnings");
        if (backend.isBlank()) {
            throw new IllegalArgumentException("backend must not be blank");
        }
        warnings = List.copyOf(warnings);
    }
}

