package ai.doctruth;

import java.util.List;
import java.util.Objects;

/**
 * Rendered document view with source-map entries back to trust units.
 *
 * @param format      rendered format name.
 * @param text        rendered text.
 * @param sourceHash  hash of the source document.
 * @param contentHash hash of the rendered text.
 * @param sourceMap   offset-level source map.
 * @since 1.0.0
 */
public record TrustRenderedDocument(
        String format, String text, String sourceHash, String contentHash, List<TrustSourceMapEntry> sourceMap) {

    public TrustRenderedDocument {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sourceHash, "sourceHash");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(sourceMap, "sourceMap");
        if (format.isBlank()) {
            throw new IllegalArgumentException("format must not be blank");
        }
        if (sourceHash.isBlank()) {
            throw new IllegalArgumentException("sourceHash must not be blank");
        }
        if (contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash must not be blank");
        }
        sourceMap = List.copyOf(sourceMap);
    }
}
