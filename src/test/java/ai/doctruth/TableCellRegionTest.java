package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract tests for {@link TableCellRegion}. */
class TableCellRegionTest {

    @Test
    @DisplayName("retains zero-based row, column, and normalized bounding box")
    void retainsCellGeometry() {
        var box = new BoundingBox(10, 20, 100, 120);

        var region = new TableCellRegion(1, 2, box);

        assertThat(region.row()).isEqualTo(1);
        assertThat(region.column()).isEqualTo(2);
        assertThat(region.rowEnd()).isEqualTo(1);
        assertThat(region.columnEnd()).isEqualTo(2);
        assertThat(region.boundingBox()).isEqualTo(box);
    }

    @Test
    @DisplayName("retains row and column spans for merged cells")
    void retainsMergedCellSpan() {
        var box = new BoundingBox(10, 20, 200, 120);

        var region = new TableCellRegion(0, 0, 0, 1, box);

        assertThat(region.row()).isEqualTo(0);
        assertThat(region.rowEnd()).isEqualTo(0);
        assertThat(region.column()).isEqualTo(0);
        assertThat(region.columnEnd()).isEqualTo(1);
        assertThat(region.boundingBox()).isEqualTo(box);
    }

    @Test
    @DisplayName("rejects negative row")
    void rejectsNegativeRow() {
        assertThatThrownBy(() -> new TableCellRegion(-1, 0, new BoundingBox(1, 2, 3, 4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("row");
    }

    @Test
    @DisplayName("rejects negative column")
    void rejectsNegativeColumn() {
        assertThatThrownBy(() -> new TableCellRegion(0, -1, new BoundingBox(1, 2, 3, 4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column");
    }

    @Test
    @DisplayName("rejects row span ending before row start")
    void rejectsInvalidRowSpan() {
        assertThatThrownBy(() -> new TableCellRegion(1, 0, 0, 0, new BoundingBox(1, 2, 3, 4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rowEnd");
    }

    @Test
    @DisplayName("rejects column span ending before column start")
    void rejectsInvalidColumnSpan() {
        assertThatThrownBy(() -> new TableCellRegion(0, 1, 0, 0, new BoundingBox(1, 2, 3, 4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columnEnd");
    }

    @Test
    @DisplayName("rejects null bounding box")
    void rejectsNullBoundingBox() {
        assertThatThrownBy(() -> new TableCellRegion(0, 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("boundingBox");
    }
}
