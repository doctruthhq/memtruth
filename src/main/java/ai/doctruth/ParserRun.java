package ai.doctruth;

import java.util.Objects;

/**
 * Parser provenance attached to a TrustDocument.
 *
 * @param backend parser backend identifier, for example {@code opendataloader}.
 * @since 0.2.0
 */
public record ParserRun(String backend) {

    public ParserRun {
        Objects.requireNonNull(backend, "backend");
        if (backend.isBlank()) {
            throw new IllegalArgumentException("backend must not be blank");
        }
    }
}
