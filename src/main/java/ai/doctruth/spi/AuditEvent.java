package ai.doctruth.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A single audit event emitted by the library — extraction success, extraction failure,
 * citation-below-threshold, etc. {@code attributes} is an open map for SIEM-side
 * categorisation (provider name, retry count, error code, field path).
 *
 * <p>{@code kind} convention: {@code "<domain>.<verb>"} — e.g. {@code "extraction.success"},
 * {@code "extraction.failed"}, {@code "citation.below_threshold"}.
 *
 * <p>Invariants: {@code kind} non-null and non-blank; {@code at} non-null;
 * {@code attributes} non-null (empty map allowed).
 *
 * @since 0.1.0
 */
public record AuditEvent(String kind, Instant at, Map<String, String> attributes) {
    public AuditEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(attributes, "attributes");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        attributes = Map.copyOf(attributes);
    }
}
