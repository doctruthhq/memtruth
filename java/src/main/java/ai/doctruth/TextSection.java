package ai.doctruth;

import java.util.Objects;
import java.util.Optional;

/**
 * A run of plain text recovered from the source document, anchored to a {@link SourceLocation}
 * and tagged with a {@link BlockKind} that classifies the geometric / typographic shape of the
 * block (HEADING / BODY / LIST / OTHER).
 *
 * <p>Invariants: {@code text}, {@code location}, and {@code kind} are non-null. Empty
 * {@code text} is allowed (an empty paragraph or whitespace-only run is still a parsed
 * section).
 *
 * <p>The two-arg convenience constructor exists for backward compatibility with v0.1.0
 * callers that pre-date {@link BlockKind}; it defaults {@code kind} to {@link BlockKind#OTHER}.
 * New code that classifies layout SHOULD prefer the three-arg form.
 *
 * @param text     the recovered text run.
 * @param location the source-document span this text was recovered from.
 * @param kind        the geometric / typographic classification of the block.
 * @param boundingBox optional page-normalized visual region for PDF-originated text.
 * @since 0.1.0
 */
public record TextSection(String text, SourceLocation location, BlockKind kind, Optional<BoundingBox> boundingBox)
        implements ParsedSection {

    public TextSection {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(boundingBox, "boundingBox");
    }

    /**
     * Backward-compat 3-arg constructor — leaves the visual bounding box absent.
     */
    public TextSection(String text, SourceLocation location, BlockKind kind) {
        this(text, location, kind, Optional.empty());
    }

    /**
     * Backward-compat 2-arg constructor — defaults {@code kind} to {@link BlockKind#OTHER}.
     * Preserved for callers that pre-date the {@link BlockKind} upgrade; new code that
     * classifies layout should prefer the 3-arg form.
     */
    public TextSection(String text, SourceLocation location) {
        this(text, location, BlockKind.OTHER);
    }
}
