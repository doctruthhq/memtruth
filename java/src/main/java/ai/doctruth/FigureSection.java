package ai.doctruth;

import java.util.Objects;

/**
 * A figure (image, chart, diagram) recovered from the source document, represented by its
 * caption text plus a {@link SourceLocation}. The image bytes themselves are not carried by
 * this record — downstream consumers can re-fetch from the source PDF/DOCX using the location.
 *
 * <p>Invariants: {@code caption} and {@code location} are non-null. Empty {@code caption} is
 * allowed (some figures have no caption).
 *
 * @param caption  the figure's caption text, possibly empty.
 * @param location the source-document span this figure was recovered from.
 * @since 0.1.0
 */
public record FigureSection(String caption, SourceLocation location) implements ParsedSection {

    public FigureSection {
        Objects.requireNonNull(caption, "caption");
        Objects.requireNonNull(location, "location");
    }
}
