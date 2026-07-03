package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract tests for stable v1 reading order. */
class ReadingOrderContractTest {

    private static final DocumentMetadata META = new DocumentMetadata("resume.pdf", 1, Optional.empty());
    private static final ParserRun PARSER_RUN = new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of());

    @Test
    @DisplayName("adapted units receive contiguous reading-order indexes")
    void adaptedUnitsReceiveContiguousReadingOrder() {
        var parsed = new ParsedDocument(
                "doc-1",
                List.of(
                        section("Left sidebar contact", 1),
                        new TableSection(List.of(List.of("Company", "Role"), List.of("Acme", "Engineer")), loc(2)),
                        section("Main summary", 3)),
                META);

        var doc = TrustDocument.fromParsed(parsed, "sha256:source", PARSER_RUN);

        assertThat(doc.body().units())
                .extracting(unit -> unit.location().readingOrder())
                .containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(doc.toCompactLlm())
                .containsSubsequence("Left sidebar contact", "Company", "Role", "Acme", "Engineer", "Main summary");
    }

    private static TextSection section(String text, int line) {
        return new TextSection(text, loc(line), BlockKind.BODY, Optional.empty());
    }

    private static SourceLocation loc(int line) {
        return new SourceLocation(1, 1, line, line, line * 100);
    }
}
