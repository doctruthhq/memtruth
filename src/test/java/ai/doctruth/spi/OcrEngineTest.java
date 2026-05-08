package ai.doctruth.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OcrEngineTest {

    private static BufferedImage smallImage() {
        return new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
    }

    @Nested
    @DisplayName("NOOP default")
    class Noop {

        @Test
        @DisplayName("NOOP returns empty OcrPageResult for any page")
        void noopReturnsEmpty() {
            var result = OcrEngine.NOOP.ocr(smallImage(), 1);

            assertThat(result.text()).isEmpty();
            assertThat(result.confidence()).isZero();
            assertThat(result.regions()).isEmpty();
            assertThat(result.pageNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("NOOP throws NPE when image is null — fail loud, not silent")
        void noopRejectsNullImage() {
            assertThatNullPointerException()
                    .isThrownBy(() -> OcrEngine.NOOP.ocr(null, 1))
                    .withMessageContaining("pageImage");
        }
    }

    @Nested
    @DisplayName("OcrPageResult invariants")
    class PageResult {

        @Test
        @DisplayName("happy path round-trips text + confidence + regions + pageNumber")
        void happyPath() {
            var region = new OcrRegion("Acme", 100, 200, 80, 20, 0.95);
            var result = new OcrPageResult("Acme Corp", 0.92, List.of(region), 1);

            assertThat(result.text()).isEqualTo("Acme Corp");
            assertThat(result.confidence()).isEqualTo(0.92);
            assertThat(result.regions()).hasSize(1);
            assertThat(result.pageNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("text null rejected with NPE")
        void nullText() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new OcrPageResult(null, 0.5, List.of(), 1))
                    .withMessageContaining("text");
        }

        @Test
        @DisplayName("confidence NaN rejected with IAE")
        void nanConfidence() {
            assertThatThrownBy(() -> new OcrPageResult("x", Double.NaN, List.of(), 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("confidence");
        }

        @Test
        @DisplayName("confidence > 1.0 rejected with IAE")
        void confidenceTooHigh() {
            assertThatThrownBy(() -> new OcrPageResult("x", 1.001, List.of(), 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("confidence");
        }

        @Test
        @DisplayName("pageNumber < 1 rejected with IAE")
        void pageNumberZero() {
            assertThatThrownBy(() -> new OcrPageResult("x", 0.5, List.of(), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pageNumber");
        }

        @Test
        @DisplayName("regions list is defensively copied — input mutation does not leak")
        void regionsDefensivelyCopied() {
            var mutable = new java.util.ArrayList<OcrRegion>();
            mutable.add(new OcrRegion("A", 1, 2, 10, 10, 0.9));
            var result = new OcrPageResult("A", 0.9, mutable, 1);
            mutable.add(new OcrRegion("ROGUE", 1, 2, 10, 10, 0.9));

            assertThat(result.regions()).hasSize(1);
        }

        @Test
        @DisplayName("empty(pageNumber) factory produces blank page result")
        void emptyFactory() {
            var result = OcrPageResult.empty(7);

            assertThat(result.text()).isEmpty();
            assertThat(result.confidence()).isZero();
            assertThat(result.regions()).isEmpty();
            assertThat(result.pageNumber()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("OcrRegion invariants")
    class Region {

        @Test
        @DisplayName("happy path round-trip")
        void happyPath() {
            var r = new OcrRegion("hello", 100, 200, 80, 20, 0.95);

            assertThat(r.text()).isEqualTo("hello");
            assertThat(r.x()).isEqualTo(100);
            assertThat(r.y()).isEqualTo(200);
            assertThat(r.width()).isEqualTo(80);
            assertThat(r.height()).isEqualTo(20);
            assertThat(r.confidence()).isEqualTo(0.95);
        }

        @Test
        @DisplayName("record component count stays within the canonical agent limit")
        void recordComponentCount() {
            assertThat(OcrRegion.class.getRecordComponents()).hasSizeLessThanOrEqualTo(5);
        }

        @Test
        @DisplayName("negative x rejected with IAE")
        void negativeX() {
            assertThatThrownBy(() -> new OcrRegion("x", -1, 0, 1, 1, 0.5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("x");
        }

        @Test
        @DisplayName("zero width rejected with IAE")
        void zeroWidth() {
            assertThatThrownBy(() -> new OcrRegion("x", 0, 0, 0, 1, 0.5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("width");
        }

        @Test
        @DisplayName("zero height rejected with IAE")
        void zeroHeight() {
            assertThatThrownBy(() -> new OcrRegion("x", 0, 0, 1, 0, 0.5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("height");
        }
    }

    @Nested
    @DisplayName("custom impl plugs in")
    class CustomImpl {

        @Test
        @DisplayName("a lambda OcrEngine impl is accepted (functional interface)")
        void lambdaImpl() {
            OcrEngine fakeOcr =
                    (img, page) -> new OcrPageResult("extracted text from page " + page, 0.85, List.of(), page);

            var result = fakeOcr.ocr(smallImage(), 3);

            assertThat(result.text()).contains("page 3");
            assertThat(result.confidence()).isEqualTo(0.85);
        }
    }
}
