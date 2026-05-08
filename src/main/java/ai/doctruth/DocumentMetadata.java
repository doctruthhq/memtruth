package ai.doctruth;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Metadata for a {@link ParsedDocument}: the source filename, total page count, and
 * (optionally) the timestamp at which the source document was authored / published.
 *
 * <p>Invariants (enforced by the compact constructor):
 *
 * <ul>
 *   <li>{@code sourceFilename} is non-null and non-blank (any whitespace-only string rejected).
 *   <li>{@code pageCount >= 1}.
 *   <li>{@code sourcePublishedAt} is a non-null {@link Optional}; pass {@link Optional#empty()},
 *       not {@code null}, when unknown.
 * </ul>
 *
 * @param sourceFilename    the original filename of the source document.
 * @param pageCount         total number of pages in the source document; {@code >= 1}.
 * @param sourcePublishedAt UTC timestamp at which the source was authored, if known.
 * @since 0.1.0
 */
public record DocumentMetadata(String sourceFilename, int pageCount, Optional<Instant> sourcePublishedAt) {

    public DocumentMetadata {
        Objects.requireNonNull(sourceFilename, "sourceFilename");
        Objects.requireNonNull(sourcePublishedAt, "sourcePublishedAt");
        if (sourceFilename.isBlank()) {
            throw new IllegalArgumentException("sourceFilename must not be blank");
        }
        if (pageCount < 1) {
            throw new IllegalArgumentException("pageCount must be >= 1, got " + pageCount);
        }
    }
}
