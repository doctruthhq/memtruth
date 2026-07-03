package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link TableSection}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code rows} is non-null. An empty list IS allowed (a zero-row table is a valid parse
 *       output — e.g. a header-only table whose body got truncated upstream).
 *   <li>No inner row may be null.
 *   <li>{@code location} is non-null.
 * </ul>
 *
 * <p>Defensive-copy contract: the record stores an unmodifiable, structurally-stable copy of
 * the input. Mutating the original input list afterwards must not be observable through
 * {@code rows()}, and the returned list must reject mutation.
 */
class TableSectionTest {

    private static final SourceLocation LOC = new SourceLocation(1, 1, 1, 1, 0);

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a non-empty table with multiple rows and columns")
        void nonEmptyTable() {
            var rows = List.of(List.of("name", "age"), List.of("Alice", "30"), List.of("Bob", "25"));

            var section = new TableSection(rows, LOC);

            assertThat(section.rows()).hasSize(3);
            assertThat(section.rows().get(0)).containsExactly("name", "age");
            assertThat(section.location()).isEqualTo(LOC);
        }

        @Test
        @DisplayName("accepts an empty rows list (zero-row tables are valid)")
        void emptyRowsAllowed() {
            var section = new TableSection(List.of(), LOC);

            assertThat(section.rows()).isEmpty();
        }

        @Test
        @DisplayName("is assignable to ParsedSection (sealed interface acceptance)")
        void isParsedSection() {
            ParsedSection section = new TableSection(List.of(List.of("x")), LOC);

            assertThat(section).isInstanceOf(TableSection.class);
        }

        @Test
        @DisplayName("retains an optional table region bounding box")
        void retainsBoundingBox() {
            var box = new BoundingBox(10, 20, 300, 400);

            var section = new TableSection(List.of(List.of("x")), LOC, Optional.of(box));

            assertThat(section.boundingBox()).contains(box);
        }

        @Test
        @DisplayName("retains optional table cell regions")
        void retainsCellRegions() {
            var region = new TableCellRegion(0, 0, new BoundingBox(10, 20, 100, 120));

            var section = new TableSection(
                    List.of(List.of("x")), LOC, Optional.of(new BoundingBox(0, 0, 200, 200)), List.of(region));

            assertThat(section.cellRegions()).containsExactly(region);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null rows with NullPointerException")
        void nullRows() {
            assertThatThrownBy(() -> new TableSection(null, LOC))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("rows");
        }

        @Test
        @DisplayName("rejects null location with NullPointerException")
        void nullLocation() {
            assertThatThrownBy(() -> new TableSection(List.of(List.of("a")), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("location");
        }

        @Test
        @DisplayName("rejects null bounding box optional with NullPointerException")
        void nullBoundingBoxOptional() {
            assertThatThrownBy(() -> new TableSection(List.of(List.of("a")), LOC, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("boundingBox");
        }

        @Test
        @DisplayName("rejects null cell regions list with NullPointerException")
        void nullCellRegions() {
            assertThatThrownBy(() -> new TableSection(List.of(List.of("a")), LOC, Optional.empty(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("cellRegions");
        }

        @Test
        @DisplayName("rejects a null inner row with NullPointerException")
        void nullInnerRow() {
            var rows = new ArrayList<List<String>>();
            rows.add(List.of("a", "b"));
            rows.add(null);

            assertThatThrownBy(() -> new TableSection(rows, LOC))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("row");
        }
    }

    @Nested
    @DisplayName("defensive copy")
    class DefensiveCopy {

        @Test
        @DisplayName("mutating the input rows list after construction does not affect the stored rows")
        void mutationOfInputRowsDoesNotLeak() {
            var rows = new ArrayList<List<String>>();
            rows.add(new ArrayList<>(List.of("a", "b")));

            var section = new TableSection(rows, LOC);
            rows.add(new ArrayList<>(List.of("c", "d")));

            assertThat(section.rows()).hasSize(1);
            assertThat(section.rows().get(0)).containsExactly("a", "b");
        }

        @Test
        @DisplayName("calling rows().add(...) throws UnsupportedOperationException")
        void rowsListIsUnmodifiable() {
            var section = new TableSection(new ArrayList<>(Arrays.asList(new ArrayList<>(List.of("a", "b")))), LOC);

            assertThatThrownBy(() -> section.rows().add(List.of("c", "d")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("mutating an inner row list after construction does not affect the stored row")
        void mutationOfInnerRowDoesNotLeak() {
            var inner = new ArrayList<>(List.of("a", "b"));
            var rows = new ArrayList<List<String>>();
            rows.add(inner);

            var section = new TableSection(rows, LOC);
            inner.add("c");

            assertThat(section.rows().get(0)).containsExactly("a", "b");
        }

        @Test
        @DisplayName("calling rows().get(0).add(...) throws UnsupportedOperationException")
        void innerRowIsUnmodifiable() {
            var rows = new ArrayList<List<String>>();
            rows.add(new ArrayList<>(List.of("a", "b")));

            var section = new TableSection(rows, LOC);

            assertThatThrownBy(() -> section.rows().get(0).add("c")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("calling cellRegions().add(...) throws UnsupportedOperationException")
        void cellRegionsAreUnmodifiable() {
            var section = new TableSection(
                    List.of(List.of("x")),
                    LOC,
                    Optional.empty(),
                    new ArrayList<>(List.of(new TableCellRegion(0, 0, new BoundingBox(1, 2, 3, 4)))));

            assertThatThrownBy(() -> section.cellRegions().add(new TableCellRegion(0, 1, new BoundingBox(5, 6, 7, 8))))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
