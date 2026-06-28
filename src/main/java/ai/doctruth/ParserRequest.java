package ai.doctruth;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Input to a parser backend.
 *
 * @param sourcePath          source file path.
 * @param sourceHash          stable source content hash.
 * @param parserRun           parser provenance to attach to output.
 * @param offlineMode         true when network access is forbidden.
 * @param allowModelDownloads true when backend may download model artifacts.
 * @since 1.0.0
 */
public record ParserRequest(
        Path sourcePath, String sourceHash, ParserRun parserRun, boolean offlineMode, boolean allowModelDownloads) {

    public ParserRequest {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(sourceHash, "sourceHash");
        Objects.requireNonNull(parserRun, "parserRun");
        if (sourceHash.isBlank()) {
            throw new IllegalArgumentException("sourceHash must not be blank");
        }
    }
}
