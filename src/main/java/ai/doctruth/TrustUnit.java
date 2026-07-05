package ai.doctruth;

import java.util.List;
import java.util.Objects;

/**
 * One audit-addressable content unit in a {@link TrustDocument}.
 *
 * @param id          stable unit id within the document.
 * @param type        unit type: {@code text}, {@code table}, or {@code figure}.
 * @param text        recovered text or caption; empty for table-only units.
 * @param rows        table rows; empty for non-table units.
 * @param evidence source anchor and visual evidence.
 * @since 0.2.0
 */
public record TrustUnit(
        String id, String type, String text, List<List<String>> rows, TrustUnitEvidence evidence) {

    public TrustUnit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(evidence, "evidence");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        rows = copyRows(rows);
    }

    private static List<List<String>> copyRows(List<List<String>> rows) {
        return rows.stream().map(List::copyOf).toList();
    }
}
