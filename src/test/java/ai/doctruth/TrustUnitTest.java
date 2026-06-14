package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Contract tests for the smallest citeable v1 evidence atom. */
class TrustUnitTest {

    private static final BoundingBox BOX = new BoundingBox(10, 20, 300, 80);
    private static final Confidence CONFIDENCE = new Confidence(0.88, "quote rematched");

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("represents a page-anchored citeable text block")
        void textBlockUnit() {
            var unit = sampleUnit(List.of("span-1"));

            assertThat(unit.unitId()).isEqualTo("unit-1");
            assertThat(unit.kind()).isEqualTo(TrustUnitKind.TEXT_BLOCK);
            assertThat(unit.location().page()).isEqualTo(1);
            assertThat(unit.location().boundingBox()).contains(BOX);
            assertThat(unit.location().readingOrder()).isEqualTo(7);
            assertThat(unit.content().text()).isEqualTo("Customer requires NSF certification.");
            assertThat(unit.content().sourceObjectId()).isEqualTo("text-42");
            assertThat(unit.evidence().evidenceSpanIds()).containsExactly("span-1");
            assertThat(unit.evidence().confidence()).isEqualTo(CONFIDENCE);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects blank unit id and missing kind")
        void rejectsInvalidShell() {
            assertThatThrownBy(() -> new TrustUnit(
                            " ", TrustUnitKind.TEXT_BLOCK, sampleLocation(), sampleContent(), sampleEvidence()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unitId");
            assertThatThrownBy(() -> new TrustUnit("unit-1", null, sampleLocation(), sampleContent(), sampleEvidence()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("kind");
        }

        @Test
        @DisplayName("rejects invalid page and reading-order anchors")
        void rejectsInvalidLocation() {
            assertThatThrownBy(() -> new TrustUnitLocation(0, Optional.of(BOX), 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("page");
            assertThatThrownBy(() -> new TrustUnitLocation(1, Optional.of(BOX), -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("readingOrder");
        }

        @Test
        @DisplayName("rejects blank text and source object id")
        void rejectsInvalidContent() {
            assertThatThrownBy(() -> new TrustUnitContent(" ", "text-42"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("text");
            assertThatThrownBy(() -> new TrustUnitContent("text", " "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceObjectId");
        }

        @Test
        @DisplayName("rejects blank evidence span ids")
        void rejectsInvalidEvidenceSpanId() {
            assertThatThrownBy(() -> new TrustUnitEvidence(List.of("span-1", " "), CONFIDENCE, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("evidenceSpanIds");
        }
    }

    @Nested
    @DisplayName("defensive copy")
    class DefensiveCopy {

        @Test
        @DisplayName("evidence span ids and warnings are immutable snapshots")
        void evidenceIsDefensivelyCopied() {
            var spans = new ArrayList<String>();
            spans.add("span-1");
            var warnings = new ArrayList<ParserWarning>();
            warnings.add(new ParserWarning("ocr_low_confidence", ParserWarningSeverity.SEVERE, "weak OCR"));

            var evidence = new TrustUnitEvidence(spans, CONFIDENCE, warnings);
            spans.clear();
            warnings.clear();

            assertThat(evidence.evidenceSpanIds()).containsExactly("span-1");
            assertThat(evidence.warnings()).hasSize(1);
            assertThatThrownBy(() -> evidence.evidenceSpanIds().add("span-2"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> evidence.warnings().add(new ParserWarning("x", ParserWarningSeverity.INFO, "")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    private static TrustUnit sampleUnit(List<String> evidenceSpanIds) {
        return new TrustUnit(
                "unit-1", TrustUnitKind.TEXT_BLOCK, sampleLocation(), sampleContent(), sampleEvidence(evidenceSpanIds));
    }

    private static TrustUnitLocation sampleLocation() {
        return new TrustUnitLocation(1, Optional.of(BOX), 7);
    }

    private static TrustUnitContent sampleContent() {
        return new TrustUnitContent("Customer requires NSF certification.", "text-42");
    }

    private static TrustUnitEvidence sampleEvidence() {
        return sampleEvidence(List.of("span-1"));
    }

    private static TrustUnitEvidence sampleEvidence(List<String> evidenceSpanIds) {
        return new TrustUnitEvidence(evidenceSpanIds, CONFIDENCE, List.of());
    }
}

