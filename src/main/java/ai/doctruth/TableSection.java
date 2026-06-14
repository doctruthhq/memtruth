package ai.doctruth;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A flat string-cell table recovered from the source document, anchored to a
 * {@link SourceLocation}. Each row is a list of cell strings; rows need not be the same length
 * (rendering is downstream).
 *
 * <p>Invariants: {@code rows} and {@code location} are non-null. Each inner row is non-null.
 * Empty {@code rows} is allowed (a table with zero rows is still a parsed section).
 *
 * <p>The {@code rows} field is defensively copied on construction (both outer and inner lists)
 * and exposed as an unmodifiable nested view, so neither the caller's input nor the accessor's
 * return value can mutate the section's state.
 *
 * @param rows     the table cells, row-major.
 * @param location    the source-document span this table was recovered from.
 * @param boundingBox optional normalized source-region box for the table.
 * @param cellRegions optional normalized source-region boxes for table cells.
 * @since 0.1.0
 */
public record TableSection(
        List<List<String>> rows,
        SourceLocation location,
        Optional<BoundingBox> boundingBox,
        List<TableCellRegion> cellRegions)
        implements ParsedSection {

    public TableSection(List<List<String>> rows, SourceLocation location) {
        this(rows, location, Optional.empty(), List.of());
    }

    public TableSection(List<List<String>> rows, SourceLocation location, Optional<BoundingBox> boundingBox) {
        this(rows, location, boundingBox, List.of());
    }

    public TableSection {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(boundingBox, "boundingBox");
        Objects.requireNonNull(cellRegions, "cellRegions");
        var copied = new ArrayList<List<String>>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            Objects.requireNonNull(row, "rows[" + i + "]");
            copied.add(List.copyOf(row));
        }
        rows = List.copyOf(copied);
        cellRegions = List.copyOf(cellRegions);
    }
}
