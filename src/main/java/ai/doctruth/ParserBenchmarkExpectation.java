package ai.doctruth;

import java.util.Objects;
import java.util.Optional;

/**
 * Expected benchmark outputs for metric comparison.
 *
 * @since 1.0.0
 */
public record ParserBenchmarkExpectation(String markdown, Optional<TrustDocument> document) {

    public ParserBenchmarkExpectation {
        Objects.requireNonNull(markdown, "markdown");
        Objects.requireNonNull(document, "document");
    }
}
