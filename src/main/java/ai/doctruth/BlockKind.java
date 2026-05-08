package ai.doctruth;

/**
 * Visual / structural classification of a {@link TextSection} as detected by Layer 1
 * parsing — a geometric / typographic judgement, NOT a semantic one. The library answers
 * "this looks like a heading" / "this looks like a body paragraph"; whether a heading
 * means "Section 5.3 — Indemnities" vs "Acme Corp Limited" is a semantic question the
 * LLM answers downstream. (Per the layer-separation principle: parser → context →
 * LLM → citation match → audit.)
 *
 * <p>The enum constants are frozen at v0.1.0 — adding new kinds in future requires a
 * major bump per CONTRIBUTING.md "Public API contracts".
 *
 * @since 0.1.0
 */
public enum BlockKind {
    /** A heading-like block — bigger font OR all-caps short text. */
    HEADING,
    /** A regular body paragraph. */
    BODY,
    /** A list item (bulleted or numbered). */
    LIST,
    /** Could not classify — default for parsers that don't analyse layout. */
    OTHER
}
