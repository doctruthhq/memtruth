package ai.doctruth;

import java.util.ArrayList;
import java.util.Objects;

import ai.doctruth.internal.render.SectionRenderer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixed-size character windows with optional overlap. Useful when the document is long-form
 * prose and priority-section heuristics don't apply (e.g., legal contracts read end-to-end).
 *
 * <p>Invariants (compact constructor):
 *
 * <ul>
 *   <li>{@code windowChars >= 1}.
 *   <li>{@code overlapChars >= 0} and {@code overlapChars < windowChars}.
 *       Equal-or-greater overlap would either spin in place or run backwards.
 * </ul>
 *
 * <p><b>Phase-1 single-window {@code assemble}.</b> The strategy renders every section via
 * {@link SectionRenderer}, joins them with {@code "\n\n"} into a single string {@code full},
 * and returns ONE bounded prefix:
 *
 * <ul>
 *   <li>If {@code full.length() <= windowChars}: return {@code full} verbatim.
 *   <li>Otherwise: return {@code full.substring(0, windowChars)} — strict cap, never exceeds.
 *   <li>Empty document (zero sections): return {@code ""}.
 * </ul>
 *
 * <p>The {@code overlapChars} field is <b>reserved for v0.2.0+ multi-window iteration</b> and
 * is intentionally NOT consulted by this single-window {@code assemble}. Multi-window
 * orchestration (where the strategy emits successive overlapping windows for sequential
 * downstream LLM calls) arrives in v0.2.0+ when {@code ContextStrategy} is widened to return a
 * richer {@code AssembledContext} record.
 *
 * <p>Why so minimal? Three reasons (per AGENTS.md "Engineering principles"):
 *
 * <ol>
 *   <li>Shipping a complex multi-window assembler before we know how callers consume it would
 *       lock us into the wrong shape.
 *   <li>A single bounded prefix is honest — predictable, audit-able, no silent drops.
 *   <li>Multi-window iteration lands when the surrounding API (Phase 3+ {@code AssembledContext})
 *       can express it cleanly.
 * </ol>
 *
 * @param windowChars  size of the assembled window in characters; strict upper bound.
 * @param overlapChars characters of overlap between consecutive windows; must be strictly
 *                     less than {@code windowChars}. Reserved for v0.2.0+; unused by the
 *                     single-window {@code assemble}.
 * @since 0.1.0
 */
public record SlidingWindow(int windowChars, int overlapChars) implements ContextStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(SlidingWindow.class);

    /** Joiner between rendered sections — matches {@link PriorityTruncate} convention. */
    private static final String JOINER = "\n\n";

    public SlidingWindow {
        if (windowChars < 1) {
            throw new IllegalArgumentException("windowChars must be >= 1, got " + windowChars);
        }
        if (overlapChars < 0) {
            throw new IllegalArgumentException("overlapChars must be >= 0, got " + overlapChars);
        }
        if (overlapChars >= windowChars) {
            throw new IllegalArgumentException("overlapChars must be < windowChars, got overlapChars=" + overlapChars
                    + " windowChars=" + windowChars);
        }
    }

    /**
     * Render {@code doc} into a single bounded-prefix window. See class Javadoc for the full
     * contract.
     */
    @Override
    public String assemble(ParsedDocument doc) {
        Objects.requireNonNull(doc, "doc");
        // overlapChars is intentionally not consulted here — reserved for v0.2.0+ multi-window.
        var rendered = new ArrayList<String>(doc.sections().size());
        for (var s : doc.sections()) {
            rendered.add(SectionRenderer.render(s));
        }
        var full = String.join(JOINER, rendered);
        boolean truncated = full.length() > windowChars;
        LOG.debug(
                "sliding-window assemble: full={} chars windowChars={} truncated={}",
                full.length(),
                windowChars,
                truncated);
        return truncated ? full.substring(0, windowChars) : full;
    }
}
