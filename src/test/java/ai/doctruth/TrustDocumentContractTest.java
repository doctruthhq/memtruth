package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Contract tests for the v1 evidence-native {@link TrustDocument}. */
class TrustDocumentContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DocumentMetadata META = new DocumentMetadata("resume.pdf", 2, Optional.empty());
    private static final BoundingBox BOX = new BoundingBox(100, 120, 500, 180);
    private static final Confidence CONFIDENCE = new Confidence(0.91, "text-layer exact span");

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("carries source, units, parser run, and audit status")
        void carriesCanonicalTrustShape() {
            var warning =
                    new ParserWarning("reading_order_uncertain", ParserWarningSeverity.SEVERE, "two columns overlap");
            var unit = sampleUnit(warning);
            var doc = sampleDocument(List.of(unit), List.of(warning), AuditGradeStatus.NOT_AUDIT_GRADE);

            assertThat(doc.docId()).isEqualTo("doc-1");
            assertThat(doc.source().sourceHash()).isEqualTo("sha256:source");
            assertThat(doc.body().pages()).extracting(TrustPage::pageNumber).containsExactly(1);
            assertThat(doc.body().units()).containsExactly(unit);
            assertThat(doc.parserRun().warnings()).containsExactly(warning);
            assertThat(doc.auditGradeStatus()).isEqualTo(AuditGradeStatus.NOT_AUDIT_GRADE);
        }

        @Test
        @DisplayName("does not become audit-grade merely because it is a TrustDocument")
        void trustDocumentNameDoesNotImplyAuditGrade() {
            var doc = sampleDocument(List.of(), List.of(), AuditGradeStatus.UNKNOWN);

            assertThat(doc.auditGradeStatus()).isEqualTo(AuditGradeStatus.UNKNOWN);
        }

        @Test
        @DisplayName("round-trips parser run id through full JSON")
        void parserRunIdRoundTripsThroughFullJson() throws Exception {
            var parserRun = new ParserRun(
                    "parser-run-rust-42", "1.0.0", "standard", "rust-sidecar", List.of("layout:v2"), List.of());
            var doc = new TrustDocument(
                    "doc-1", sampleSource(), sampleBody(List.of()), parserRun, AuditGradeStatus.UNKNOWN);

            String json = doc.toJsonFull();
            var loaded = TrustDocument.fromJsonFull(json);

            assertThat(MAPPER.readTree(json)
                            .path("parserRun")
                            .path("parserRunId")
                            .asText())
                    .isEqualTo("parser-run-rust-42");
            assertThat(loaded.parserRun().parserRunId()).isEqualTo("parser-run-rust-42");
            assertThat(loaded.parserRun()).isEqualTo(parserRun);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects blank document id and null grouped records")
        void rejectsInvalidDocumentShell() {
            assertThatThrownBy(() -> new TrustDocument(
                            " ",
                            sampleSource(),
                            sampleBody(List.of()),
                            sampleParserRun(List.of()),
                            AuditGradeStatus.UNKNOWN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("docId");
            assertThatThrownBy(() -> new TrustDocument(
                            "doc-1", null, sampleBody(List.of()), sampleParserRun(List.of()), AuditGradeStatus.UNKNOWN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("source");
        }

        @Test
        @DisplayName("rejects blank source hash and source filename")
        void rejectsInvalidSource() {
            assertThatThrownBy(() -> new TrustDocumentSource("resume.pdf", " ", META))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceHash");
            assertThatThrownBy(() -> new TrustDocumentSource(" ", "sha256:source", META))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceFilename");
        }

        @Test
        @DisplayName("rejects parser run without backend identity")
        void rejectsInvalidParserRun() {
            assertThatThrownBy(() -> new ParserRun("1.0.0", "standard", " ", List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("backend");
            assertThatThrownBy(() -> new ParserRun(" ", "1.0.0", "standard", "pdfbox", List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parserRunId");
            assertThatThrownBy(() -> new ParserRun("1.0.0", "standard", "pdfbox", List.of(" "), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("models");
        }

        @Test
        @DisplayName("rejects invalid page geometry and image hash")
        void rejectsInvalidPageGeometry() {
            assertThatThrownBy(() -> new TrustPage(0, 1000, 1000, true, "sha256:page"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pageNumber");
            assertThatThrownBy(() -> new TrustPage(1, Double.NaN, 1000, true, "sha256:page"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("width");
            assertThatThrownBy(() -> new TrustPage(1, 1000, 0, true, "sha256:page"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("height");
            assertThatThrownBy(() -> new TrustPage(1, 1000, 1000, true, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("imageHash");
        }

        @Test
        @DisplayName("rejects invalid rendered source-map ranges")
        void rejectsInvalidSourceMapEntries() {
            assertThatThrownBy(() -> new TrustSourceMapEntry(-1, 1, "unit-1", List.of("span-1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startOffset");
            assertThatThrownBy(() -> new TrustSourceMapEntry(2, 1, "unit-1", List.of("span-1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endOffset");
            assertThatThrownBy(() -> new TrustSourceMapEntry(0, 1, " ", List.of("span-1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unitId");
            assertThatThrownBy(() -> new TrustSourceMapEntry(0, 1, "unit-1", List.of(" ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("evidenceSpanIds");
        }

        @Test
        @DisplayName("rejects invalid rendered document shell")
        void rejectsInvalidRenderedDocument() {
            var sourceMap = List.of(new TrustSourceMapEntry(0, 1, "unit-1", List.of("span-1")));

            assertThatThrownBy(
                            () -> new TrustRenderedDocument(" ", "text", "sha256:source", "sha256:content", sourceMap))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("format");
            assertThatThrownBy(() -> new TrustRenderedDocument("markdown", "text", " ", "sha256:content", sourceMap))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceHash");
            assertThatThrownBy(() -> new TrustRenderedDocument("markdown", "text", "sha256:source", " ", sourceMap))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contentHash");
        }
    }

    @Nested
    @DisplayName("defensive copy")
    class DefensiveCopy {

        @Test
        @DisplayName("body and parser warning lists cannot be mutated through caller references")
        void bodyAndParserRunAreDefensivelyCopied() {
            var units = new ArrayList<TrustUnit>();
            units.add(sampleUnit());
            var warnings = new ArrayList<ParserWarning>();
            warnings.add(
                    new ParserWarning("section_boundary_uncertain", ParserWarningSeverity.WARNING, "weak heading"));

            var doc = sampleDocument(units, warnings, AuditGradeStatus.UNKNOWN);
            units.clear();
            warnings.clear();

            assertThat(doc.body().units()).hasSize(1);
            assertThat(doc.parserRun().warnings()).hasSize(1);
            assertThatThrownBy(() -> doc.body().units().add(sampleUnit()))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() ->
                            doc.parserRun().warnings().add(new ParserWarning("x", ParserWarningSeverity.INFO, "")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("rendered source maps cannot be mutated through caller references")
        void renderedSourceMapsAreDefensivelyCopied() {
            var evidence = new ArrayList<String>();
            evidence.add("span-1");
            var entry = new TrustSourceMapEntry(0, 5, "unit-1", evidence);
            var sourceMap = new ArrayList<TrustSourceMapEntry>();
            sourceMap.add(entry);
            var rendered =
                    new TrustRenderedDocument("markdown_clean", "hello", "sha256:source", "sha256:content", sourceMap);
            evidence.clear();
            sourceMap.clear();

            assertThat(rendered.sourceMap()).containsExactly(entry);
            assertThat(rendered.sourceMap().getFirst().evidenceSpanIds()).containsExactly("span-1");
            assertThatThrownBy(() -> rendered.sourceMap().add(entry)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() ->
                            rendered.sourceMap().getFirst().evidenceSpanIds().add("span-2"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    private static TrustDocument sampleDocument(
            List<TrustUnit> units, List<ParserWarning> warnings, AuditGradeStatus status) {
        return new TrustDocument("doc-1", sampleSource(), sampleBody(units), sampleParserRun(warnings), status);
    }

    private static TrustDocumentSource sampleSource() {
        return new TrustDocumentSource("resume.pdf", "sha256:source", META);
    }

    private static TrustDocumentBody sampleBody(List<TrustUnit> units) {
        return new TrustDocumentBody(List.of(new TrustPage(1, 1000, 1000, true, "sha256:page-1")), units, List.of());
    }

    private static ParserRun sampleParserRun(List<ParserWarning> warnings) {
        return new ParserRun("1.0.0", "standard", "pdfbox", List.of("layout:none"), warnings);
    }

    private static TrustUnit sampleUnit() {
        return sampleUnit(new ParserWarning("none", ParserWarningSeverity.INFO, ""));
    }

    private static TrustUnit sampleUnit(ParserWarning warning) {
        return new TrustUnit(
                "unit-1",
                TrustUnitKind.TEXT_BLOCK,
                new TrustUnitLocation(1, Optional.of(BOX), 10),
                new TrustUnitContent("Work Experience", "text-1"),
                new TrustUnitEvidence(List.of("span-1"), CONFIDENCE, List.of(warning)));
    }
}
