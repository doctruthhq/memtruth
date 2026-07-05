package ai.doctruth;

import java.util.Objects;

/**
 * TrustDocument source identity for a citation.
 *
 * @param docId  TrustDocument document id.
 * @param unitId TrustDocument unit id inside {@code docId}.
 * @since 0.2.0
 */
public record CitationSource(String docId, String unitId) {

    public CitationSource {
        Objects.requireNonNull(docId, "docId");
        Objects.requireNonNull(unitId, "unitId");
        if (docId.isBlank()) {
            throw new IllegalArgumentException("docId must not be blank");
        }
        if (unitId.isBlank()) {
            throw new IllegalArgumentException("unitId must not be blank");
        }
    }
}
