package ai.doctruth;

/**
 * How a {@link ParsedDocument} is rendered into the bounded context window of an LLM call.
 * Sealed; the only permitted strategies are {@link PriorityTruncate}, {@link SlidingWindow},
 * and {@link Hierarchical}.
 *
 * <p>Adding a new strategy requires a major version bump and a new {@code permits} entry —
 * the sealed contract makes every consumer's exhaustive {@code switch} fail to compile,
 * which is the desired forcing function.
 *
 * @since 0.1.0
 */
public sealed interface ContextStrategy permits PriorityTruncate, SlidingWindow, Hierarchical {

    /**
     * Render the document into a single user-prompt string, applying this strategy's
     * truncation / windowing rules.
     *
     * <p>Implementations MUST NOT silently overrun the strategy's documented budget — per
     * AGENTS.md "Engineering principles" §2 (no silent failures) and the production lessons
     * codified in {@link OverBudgetPolicy}. Callers either get a result inside the
     * budget, a clearly-flagged warning + an over-budget result, or an
     * {@link ExtractionException}.
     *
     * @param doc the parsed document; must not be null.
     * @return a single string suitable for use as the user prompt.
     * @throws ExtractionException if the strategy cannot satisfy its budget contract
     *     (e.g. {@link PriorityTruncate} {@link OverBudgetPolicy#STRICT} with priority
     *     sections exceeding {@code maxChars}).
     * @throws NullPointerException if {@code doc} is null.
     */
    String assemble(ParsedDocument doc) throws ExtractionException;
}
