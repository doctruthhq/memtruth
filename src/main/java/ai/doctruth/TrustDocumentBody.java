package ai.doctruth;

import java.util.List;
import java.util.Objects;

/**
 * Body objects that make a {@link TrustDocument} citeable.
 *
 * @param pages  page-level anchors.
 * @param units  smallest citeable units.
 * @param tables structured tables.
 * @since 1.0.0
 */
public record TrustDocumentBody(List<TrustPage> pages, List<TrustUnit> units, List<TrustTable> tables) {

    public TrustDocumentBody {
        Objects.requireNonNull(pages, "pages");
        Objects.requireNonNull(units, "units");
        Objects.requireNonNull(tables, "tables");
        pages = List.copyOf(pages);
        units = List.copyOf(units);
        tables = List.copyOf(tables);
    }
}

