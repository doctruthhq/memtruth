package ai.doctruth;

import java.util.Objects;

/**
 * Stable source identity for a TrustDocument.
 *
 * @param filename original source filename.
 * @param sha256   lowercase SHA-256 digest of the source bytes.
 * @param pageCount source page count when known by the parser.
 * @since 0.2.0
 */
public record TrustDocumentSource(String filename, String sha256, int pageCount) {

    public TrustDocumentSource {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(sha256, "sha256");
        if (filename.isBlank()) {
            throw new IllegalArgumentException("filename must not be blank");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a lowercase hex SHA-256 digest");
        }
        if (pageCount < 1) {
            throw new IllegalArgumentException("pageCount must be >= 1");
        }
    }
}
