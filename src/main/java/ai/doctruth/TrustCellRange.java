package ai.doctruth;

/**
 * Inclusive table row or column span.
 *
 * @param start zero-indexed inclusive start.
 * @param end   zero-indexed inclusive end.
 * @since 1.0.0
 */
public record TrustCellRange(int start, int end) {

    public TrustCellRange {
        if (start < 0) {
            throw new IllegalArgumentException("start must be >= 0");
        }
        if (end < start) {
            throw new IllegalArgumentException("end must be >= start");
        }
    }
}
