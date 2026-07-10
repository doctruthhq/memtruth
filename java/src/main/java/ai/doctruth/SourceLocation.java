package ai.doctruth;

/**
 * A 1-indexed page + line span into a parsed source document, plus a 0-indexed character
 * offset into the source page text. Used as the verifiable anchor for every {@link Citation}.
 *
 * <p>Invariants (enforced by the compact constructor):
 *
 * <ul>
 *   <li>{@code pageStart >= 1} — pages are 1-indexed (PDF/PDFBox convention).
 *   <li>{@code pageEnd >= pageStart}.
 *   <li>{@code lineStart >= 1}, {@code lineEnd >= 1} — line numbers are per-page, 1-indexed.
 *   <li>{@code charOffset >= 0}.
 *   <li>If {@code pageStart == pageEnd}, then {@code lineEnd >= lineStart}. Cross-page spans
 *       intentionally allow {@code lineEnd < lineStart} because line numbers reset per page.
 * </ul>
 *
 * @param pageStart  1-indexed first page of the span.
 * @param pageEnd    1-indexed last page of the span (inclusive); {@code >= pageStart}.
 * @param lineStart  1-indexed first line on {@code pageStart}.
 * @param lineEnd    1-indexed last line on {@code pageEnd} (inclusive).
 * @param charOffset 0-indexed character offset into the source page text where the span begins.
 * @since 0.1.0
 */
public record SourceLocation(int pageStart, int pageEnd, int lineStart, int lineEnd, int charOffset) {

    public SourceLocation {
        if (pageStart < 1) {
            throw new IllegalArgumentException("pageStart must be >= 1, got " + pageStart);
        }
        if (pageEnd < pageStart) {
            throw new IllegalArgumentException(
                    "pageEnd must be >= pageStart, got pageEnd=" + pageEnd + " pageStart=" + pageStart);
        }
        if (lineStart < 1) {
            throw new IllegalArgumentException("lineStart must be >= 1, got " + lineStart);
        }
        if (lineEnd < 1) {
            throw new IllegalArgumentException("lineEnd must be >= 1, got " + lineEnd);
        }
        if (charOffset < 0) {
            throw new IllegalArgumentException("charOffset must be >= 0, got " + charOffset);
        }
        if (pageStart == pageEnd && lineEnd < lineStart) {
            throw new IllegalArgumentException("intra-page span requires lineEnd >= lineStart, got lineStart="
                    + lineStart + " lineEnd=" + lineEnd + " on page " + pageStart);
        }
    }
}
