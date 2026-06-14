package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract tests for replay validation of TrustDocument audit packages. */
class TrustAuditVerifierTest {

    private static final SourceLocation LOC = new SourceLocation(1, 1, 1, 1, 0);
    private static final ParserRun PARSER_RUN = new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of());

    @Test
    @DisplayName("verifier accepts audit JSON generated from the same TrustDocument")
    void acceptsMatchingAuditPackage() {
        var doc = document();

        assertThatCode(() -> TrustAuditVerifier.verify(doc, doc.toAuditJson())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("verifier rejects tampered evidence payloads even when metadata still parses")
    void rejectsTamperedEvidencePayload() {
        var doc = document();
        String tampered = doc.toAuditJson().replace("Work Experience", "Tampered Experience");

        assertThatThrownBy(() -> TrustAuditVerifier.verify(doc, tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
    }

    @Test
    @DisplayName("verifier rejects audit JSON whose canonical hash no longer matches the TrustDocument")
    void rejectsCanonicalHashMismatch() {
        var doc = document();
        String tampered = doc.toAuditJson().replace(doc.canonicalHash(), "sha256:bad");

        assertThatThrownBy(() -> TrustAuditVerifier.verify(doc, tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonicalHash");
    }

    @Test
    @DisplayName("TrustDocument JSON round-trips for replay verification")
    void trustDocumentCanBeLoadedFromJsonFull() {
        var doc = document();

        var loaded = TrustDocument.fromJsonFull(doc.toJsonFull());

        assertThatCode(() -> TrustAuditVerifier.verify(loaded, doc.toAuditJson())).doesNotThrowAnyException();
    }

    private static TrustDocument document() {
        var parsed = new ParsedDocument(
                "doc-audit",
                List.of(new TextSection(
                        "Work Experience",
                        LOC,
                        BlockKind.HEADING,
                        Optional.of(new BoundingBox(100, 100, 500, 200)))),
                new DocumentMetadata("resume.pdf", 1, Optional.empty()));
        return TrustDocument.fromParsed(parsed, "sha256:source", PARSER_RUN).withEvaluatedAuditGrade();
    }
}
