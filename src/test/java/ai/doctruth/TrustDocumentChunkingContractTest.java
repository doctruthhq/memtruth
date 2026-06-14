package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract tests for LLM/RAG chunk output from TrustDocument. */
class TrustDocumentChunkingContractTest {

    private static final DocumentMetadata META = new DocumentMetadata("resume.pdf", 1, Optional.empty());
    private static final ParserRun PARSER_RUN = new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of());

    @Test
    @DisplayName("chunks preserve unit ids, evidence ids, and reading order")
    void chunksPreserveEvidenceAndReadingOrder() {
        var doc = TrustDocument.fromParsed(
                new ParsedDocument(
                        "doc-1",
                        List.of(
                                section("Professional summary", 1),
                                section("Candidate has logistics experience in Perodua transport.", 2),
                                section("Candidate speaks Bahasa Melayu and English.", 3)),
                        META),
                "sha256:source",
                PARSER_RUN);

        var chunks = doc.toChunks(80);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().unitIds()).containsExactly("unit-0001", "unit-0002");
        assertThat(chunks.getFirst().evidenceSpanIds()).containsExactly("span-0001", "span-0002");
        assertThat(chunks.getFirst().text())
                .contains("Professional summary")
                .contains("Perodua transport");
        assertThat(chunks.get(1).unitIds()).containsExactly("unit-0003");
        assertThat(chunks.get(1).evidenceSpanIds()).containsExactly("span-0003");
    }

    @Test
    @DisplayName("chunk size must leave room for at least one meaningful unit")
    void rejectsTinyChunkSize() {
        var doc = TrustDocument.fromParsed(
                new ParsedDocument("doc-1", List.of(section("Professional summary", 1)), META),
                "sha256:source",
                PARSER_RUN);

        assertThatThrownBy(() -> doc.toChunks(15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxChars");
    }

    private static TextSection section(String text, int line) {
        return new TextSection(
                text,
                new SourceLocation(1, 1, line, line, line * 100),
                BlockKind.BODY,
                Optional.empty());
    }
}
