/**
 * Internal citation-matching primitives. NOT public API.
 *
 * <p>The {@link ai.doctruth.internal.citation.CitationMatcher} walks an extracted Java
 * record reflectively and matches each leaf string back to the source document via
 * exact-substring → fuzzy ({@code JaroWinklerSimilarity}) — emitting an SLF4J warning,
 * never failing silently, when no match is above threshold (per ADR 0005 + CONTRIBUTING.md
 * Engineering Principles §2).
 *
 * <p>Apache Commons Text is confined to this package; no concrete Commons Text type
 * appears in the public {@code ai.doctruth.*} API.
 *
 * @hidden
 */
package ai.doctruth.internal.citation;
