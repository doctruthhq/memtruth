package ai.doctruth;

import java.util.Objects;

/**
 * Multi-level summarisation: condense the document at increasing granularities, hand the LLM
 * the level that fits the budget. Placeholder shape — Phase 3 will decide whether to extend
 * the record's components (per-level summarisers, fan-out, etc.) or split into a builder.
 *
 * <p>Invariants (compact constructor):
 *
 * <ul>
 *   <li>{@code maxDepth >= 1}.
 * </ul>
 *
 * @param maxDepth maximum number of summarisation levels (1 = single pass).
 * @since 0.1.0
 */
public record Hierarchical(int maxDepth) implements ContextStrategy {

    public Hierarchical {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got " + maxDepth);
        }
    }

    /**
     * v0.1.0-alpha stub: not yet implemented. Phase 3 wires up multi-level summarisation; the
     * audit-able {@link UnsupportedOperationException} marker is intentional (per AGENTS.md
     * §2 — no silent failures) so any caller using the stub fails loudly.
     */
    @Override
    public String assemble(ParsedDocument doc) {
        Objects.requireNonNull(doc, "doc");
        throw new UnsupportedOperationException(
                "Hierarchical.assemble is not yet implemented; arrives in the planned context-assembly phase");
    }
}
