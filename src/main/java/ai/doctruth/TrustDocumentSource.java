package ai.doctruth;

import java.util.Objects;

/**
 * Source identity for a {@link TrustDocument}.
 *
 * @param sourceFilename original source filename.
 * @param sourceHash     stable content hash.
 * @param metadata       existing document metadata.
 * @since 1.0.0
 */
public record TrustDocumentSource(String sourceFilename, String sourceHash, DocumentMetadata metadata) {

    public TrustDocumentSource {
        Objects.requireNonNull(sourceFilename, "sourceFilename");
        Objects.requireNonNull(sourceHash, "sourceHash");
        Objects.requireNonNull(metadata, "metadata");
        if (sourceFilename.isBlank()) {
            throw new IllegalArgumentException("sourceFilename must not be blank");
        }
        if (sourceHash.isBlank()) {
            throw new IllegalArgumentException("sourceHash must not be blank");
        }
    }
}
