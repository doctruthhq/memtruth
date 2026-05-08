package ai.doctruth;

import java.util.List;
import java.util.Objects;

/**
 * The output of Layer 1 (document parsing): a stable identifier, an ordered list of
 * {@link ParsedSection sections}, and the document {@link DocumentMetadata metadata}.
 *
 * <p>Invariants (enforced by the compact constructor):
 *
 * <ul>
 *   <li>{@code docId} is non-null and non-blank.
 *   <li>{@code sections} is non-null (empty list allowed).
 *   <li>{@code metadata} is non-null.
 * </ul>
 *
 * <p>The {@code sections} list is defensively copied on construction and exposed as
 * unmodifiable, so neither the caller's input nor the accessor's return value can mutate
 * the document's state.
 *
 * @param docId    a stable identifier for this document (e.g. content hash, UUID, file path).
 * @param sections the parsed sections in document order.
 * @param metadata document-level metadata.
 * @since 0.1.0
 */
public record ParsedDocument(String docId, List<ParsedSection> sections, DocumentMetadata metadata) {

    public ParsedDocument {
        Objects.requireNonNull(docId, "docId");
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(metadata, "metadata");
        if (docId.isBlank()) {
            throw new IllegalArgumentException("docId must not be blank");
        }
        sections = List.copyOf(sections);
    }
}
