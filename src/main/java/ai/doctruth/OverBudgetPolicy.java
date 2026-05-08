package ai.doctruth;

/**
 * What a {@link PriorityTruncate} strategy does when the priority sections alone exceed the
 * configured {@code maxChars} budget. Prior production systems often handled this by silently
 * overrunning the budget; this library makes the behavior explicit.
 *
 * <ul>
 *   <li>{@link #STRICT} — throw an {@link ExtractionException} rather than exceed the budget.
 *       Use when the caller has hard cost / latency constraints.
 *   <li>{@link #WARN_AND_INCLUDE} — log an SLF4J warning, include the priority sections
 *       anyway, and surface the overrun in {@code AssembledContext}. The default keeps the
 *       caller moving while making the overrun auditable.
 * </ul>
 *
 * @since 0.1.0
 */
public enum OverBudgetPolicy {
    STRICT,
    WARN_AND_INCLUDE
}
