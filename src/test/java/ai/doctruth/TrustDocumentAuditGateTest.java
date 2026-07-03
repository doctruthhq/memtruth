package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract tests for v1 audit-grade gating. */
class TrustDocumentAuditGateTest {

    private static final DocumentMetadata META = new DocumentMetadata("resume.pdf", 1, Optional.empty());
    private static final SourceLocation LOC = new SourceLocation(1, 1, 1, 1, 0);

    @Test
    @DisplayName("marks document audit-grade when units have evidence and no severe warnings")
    void auditGradeWhenEvidenceIsClean() {
        var doc = document(List.of(), List.of(unit(List.of())));

        assertThat(doc.withEvaluatedAuditGrade().auditGradeStatus()).isEqualTo(AuditGradeStatus.AUDIT_GRADE);
    }

    @Test
    @DisplayName("blocks audit-grade when parser run has severe warning")
    void severeParserWarningBlocksAuditGrade() {
        var severe = new ParserWarning("reading_order_uncertain", ParserWarningSeverity.SEVERE, "ambiguous columns");
        var doc = document(List.of(severe), List.of(unit(List.of())));

        assertThat(doc.withEvaluatedAuditGrade().auditGradeStatus()).isEqualTo(AuditGradeStatus.NOT_AUDIT_GRADE);
    }

    @Test
    @DisplayName("blocks audit-grade when unit has severe warning")
    void severeUnitWarningBlocksAuditGrade() {
        var severe = new ParserWarning("quote_anchor_failed", ParserWarningSeverity.SEVERE, "quote did not rematch");
        var doc = document(List.of(), List.of(unit(List.of(severe))));

        assertThat(doc.withEvaluatedAuditGrade().auditGradeStatus()).isEqualTo(AuditGradeStatus.NOT_AUDIT_GRADE);
    }

    @Test
    @DisplayName("blocks audit-grade when document has no citeable evidence units")
    void noUnitsBlocksAuditGrade() {
        var doc = document(List.of(), List.of());

        assertThat(doc.withEvaluatedAuditGrade().auditGradeStatus()).isEqualTo(AuditGradeStatus.NOT_AUDIT_GRADE);
    }

    private static TrustDocument document(List<ParserWarning> parserWarnings, List<TrustUnit> units) {
        var parsed = new ParsedDocument("doc-1", List.of(new TextSection("Work Experience", LOC)), META);
        var parserRun = new ParserRun("1.0.0", "lite", "pdfbox", List.of(), parserWarnings);
        var doc = TrustDocument.fromParsed(parsed, "sha256:source", parserRun);
        return new TrustDocument(
                doc.docId(),
                doc.source(),
                new TrustDocumentBody(doc.body().pages(), units, List.of()),
                doc.parserRun(),
                AuditGradeStatus.UNKNOWN);
    }

    private static TrustUnit unit(List<ParserWarning> warnings) {
        return new TrustUnit(
                "unit-1",
                TrustUnitKind.TEXT_BLOCK,
                new TrustUnitLocation(1, Optional.empty(), 1),
                new TrustUnitContent("Work Experience", "section-1"),
                new TrustUnitEvidence(List.of("span-1"), new Confidence(1.0, "exact"), warnings));
    }
}
