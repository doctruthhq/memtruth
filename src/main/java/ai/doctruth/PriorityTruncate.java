package ai.doctruth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import ai.doctruth.internal.render.SectionRenderer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smart-context strategy for keeping priority sections while trimming everything else to fit.
 * Patterns are matched against the
 * {@link ai.doctruth.internal.render.SectionRenderer rendered} text of each section using
 * a case-insensitive substring check — kept deliberately dumb at this layer; users own the
 * vocabulary (e.g. {@code "Qualifications"}, {@code "评分标准"}).
 *
 * <p>Invariants (compact constructor):
 *
 * <ul>
 *   <li>{@code prioritySectionPatterns} non-null; each entry non-null and non-blank.
 *       Empty list is allowed (no priorities = pure character-budget truncation).
 *   <li>{@code maxChars >= 1}.
 *   <li>{@code onOverBudget} non-null.
 * </ul>
 *
 * <p>The pattern list is defensively copied on construction and exposed as unmodifiable.
 *
 * @param prioritySectionPatterns substrings (case-insensitive) that mark a section as
 *                                priority — included even when the budget is tight.
 * @param maxChars                soft character budget for the assembled context.
 * @param onOverBudget            policy for the case where priority sections alone exceed
 *                                {@code maxChars}.
 * @since 0.1.0
 */
public record PriorityTruncate(List<String> prioritySectionPatterns, int maxChars, OverBudgetPolicy onOverBudget)
        implements ContextStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(PriorityTruncate.class);

    /** Joiner between rendered sections — matches the existing {@code ExtractionBuilder} convention. */
    private static final String JOINER = "\n\n";

    public PriorityTruncate {
        Objects.requireNonNull(prioritySectionPatterns, "prioritySectionPatterns");
        Objects.requireNonNull(onOverBudget, "onOverBudget");
        for (int i = 0; i < prioritySectionPatterns.size(); i++) {
            var p = prioritySectionPatterns.get(i);
            Objects.requireNonNull(p, "prioritySectionPatterns[" + i + "]");
            if (p.isBlank()) {
                throw new IllegalArgumentException("prioritySectionPatterns[" + i + "] must not be blank");
            }
        }
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be >= 1, got " + maxChars);
        }
        prioritySectionPatterns = List.copyOf(prioritySectionPatterns);
    }

    /**
     * Assemble {@code doc} into a single user-prompt string. See {@link ContextStrategy#assemble}
     * and the class-level Javadoc for rules.
     */
    @Override
    public String assemble(ParsedDocument doc) throws ExtractionException {
        Objects.requireNonNull(doc, "doc");
        var rendered = renderAll(doc.sections());
        if (rendered.isEmpty()) {
            return "";
        }
        if (totalLength(rendered) <= maxChars) {
            return joinByPick(rendered, allTrue(rendered.size()));
        }
        return truncateToBudget(rendered);
    }

    private List<Rendered> renderAll(List<ParsedSection> sections) {
        var out = new ArrayList<Rendered>(sections.size());
        for (var s : sections) {
            var text = SectionRenderer.render(s);
            out.add(new Rendered(text, isPriority(text)));
        }
        return out;
    }

    private boolean isPriority(String renderedText) {
        if (prioritySectionPatterns.isEmpty()) {
            return false;
        }
        var hay = renderedText.toLowerCase(Locale.ROOT);
        for (var pat : prioritySectionPatterns) {
            if (hay.contains(pat.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String truncateToBudget(List<Rendered> rendered) throws ExtractionException {
        int priorityChars = priorityOnlyLength(rendered);
        if (priorityChars > maxChars) {
            return handlePriorityOverflow(rendered, priorityChars);
        }
        return foldFiller(rendered, priorityChars);
    }

    private String foldFiller(List<Rendered> rendered, int priorityChars) {
        var pick = new boolean[rendered.size()];
        int picked = 0;
        for (int i = 0; i < rendered.size(); i++) {
            if (rendered.get(i).priority()) {
                pick[i] = true;
                picked++;
            }
        }
        int used = priorityChars;
        for (int i = 0; i < rendered.size(); i++) {
            if (pick[i]) {
                continue;
            }
            int len = rendered.get(i).text().length();
            int delta = (picked == 0) ? len : JOINER.length() + len;
            if (used + delta <= maxChars) {
                pick[i] = true;
                picked++;
                used += delta;
            }
        }
        return joinByPick(rendered, pick);
    }

    private String handlePriorityOverflow(List<Rendered> rendered, int priorityChars) throws ExtractionException {
        if (onOverBudget == OverBudgetPolicy.STRICT) {
            throw new ExtractionException(
                    "CONTEXT_OVER_BUDGET",
                    "priority sections " + priorityChars
                            + " chars exceed maxChars " + maxChars
                            + "; STRICT policy refuses to overrun",
                    0);
        }
        LOG.warn("priority sections {} chars exceed maxChars {}; including anyway", priorityChars, maxChars);
        var pick = new boolean[rendered.size()];
        for (int i = 0; i < rendered.size(); i++) {
            pick[i] = rendered.get(i).priority();
        }
        return joinByPick(rendered, pick);
    }

    private static String joinByPick(List<Rendered> rendered, boolean[] pick) {
        var sb = new StringBuilder();
        for (int i = 0; i < rendered.size(); i++) {
            if (!pick[i]) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(JOINER);
            }
            sb.append(rendered.get(i).text());
        }
        return sb.toString();
    }

    private static boolean[] allTrue(int n) {
        var a = new boolean[n];
        for (int i = 0; i < n; i++) {
            a[i] = true;
        }
        return a;
    }

    private static int priorityOnlyLength(List<Rendered> rendered) {
        int sum = 0;
        boolean any = false;
        for (var r : rendered) {
            if (!r.priority()) {
                continue;
            }
            if (any) {
                sum += JOINER.length();
            }
            sum += r.text().length();
            any = true;
        }
        return sum;
    }

    private static int totalLength(List<Rendered> rendered) {
        int sum = 0;
        for (int i = 0; i < rendered.size(); i++) {
            if (i > 0) {
                sum += JOINER.length();
            }
            sum += rendered.get(i).text().length();
        }
        return sum;
    }

    /** Internal carrier — rendered text plus its priority classification. */
    private record Rendered(String text, boolean priority) {}
}
