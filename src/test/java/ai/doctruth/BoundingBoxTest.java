package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Contract tests for {@link BoundingBox}. */
class BoundingBoxTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a positive page-normalized rectangle")
        void validBox() {
            var box = new BoundingBox(10.0, 20.0, 300.0, 400.0);

            assertThat(box.x0()).isEqualTo(10.0);
            assertThat(box.y0()).isEqualTo(20.0);
            assertThat(box.x1()).isEqualTo(300.0);
            assertThat(box.y1()).isEqualTo(400.0);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects coordinates outside the 0..1000 page scale")
        void outsidePageScale() {
            assertThatThrownBy(() -> new BoundingBox(-1.0, 0.0, 10.0, 10.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("0..1000");
            assertThatThrownBy(() -> new BoundingBox(0.0, 0.0, 1001.0, 10.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("0..1000");
        }

        @Test
        @DisplayName("rejects zero or negative width and height")
        void notPositive() {
            assertThatThrownBy(() -> new BoundingBox(10.0, 0.0, 10.0, 10.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
            assertThatThrownBy(() -> new BoundingBox(0.0, 10.0, 10.0, 10.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("rejects non-finite coordinates")
        void nonFinite() {
            assertThatThrownBy(() -> new BoundingBox(Double.NaN, 0.0, 10.0, 10.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("x0");
            assertThatThrownBy(() -> new BoundingBox(0.0, 0.0, Double.POSITIVE_INFINITY, 10.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("x1");
        }
    }
}
