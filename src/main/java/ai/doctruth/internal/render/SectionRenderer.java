package ai.doctruth.internal.render;

import java.util.stream.Collectors;

import ai.doctruth.FigureSection;
import ai.doctruth.ParsedSection;
import ai.doctruth.TableSection;
import ai.doctruth.TextSection;

/**
 * Single source of truth for "render one {@link ParsedSection} to a flat string". Used by
 * the default {@code ExtractionBuilder} prompt-rendering path and by every
 * {@link ai.doctruth.ContextStrategy} that emits a flat user-prompt.
 *
 * <p>Rendering rules (kept dumb on purpose — fancier transformation belongs in a sibling
 * renderer, not in here):
 *
 * <ul>
 *   <li>{@link TextSection}: raw {@code text()}.
 *   <li>{@link TableSection}: cells joined with {@code " | "} per row, rows joined with
 *       {@code "\n"}. An empty rows list yields an empty string.
 *   <li>{@link FigureSection}: {@code "[Figure: " + caption() + "]"}.
 * </ul>
 *
 * <p>Package-private to internal callers (per CONTRIBUTING.md "Engineering principles" §1 —
 * the public API surface stays minimal). Public visibility on the static method is the
 * compromise that lets {@code ai.doctruth} root-package code call this without treating
 * the class as stable public API for downstream consumers.
 *
 * @hidden
 */
public final class SectionRenderer {

    private SectionRenderer() {
        // Utility class — instances are nonsensical. (CONTRIBUTING.md "elegance over cleverness"
        // tolerates a static helper here because the alternative — a method on every
        // ParsedSection variant — would couple the public sealed interface to a rendering
        // concern that genuinely belongs out-of-band.)
    }

    /**
     * Render a single {@link ParsedSection} to a flat string per the rules above.
     *
     * @throws NullPointerException if {@code section} is null.
     */
    public static String render(ParsedSection section) {
        return switch (section) {
            case TextSection ts -> ts.text();
            case TableSection ts ->
                ts.rows().stream().map(row -> String.join(" | ", row)).collect(Collectors.joining("\n"));
            case FigureSection fs -> "[Figure: " + fs.caption() + "]";
        };
    }
}
