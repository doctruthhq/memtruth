package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behaviour test for {@link Hierarchical#assemble(ParsedDocument)}.
 *
 * <p>{@code Hierarchical.assemble} is a Phase-3 placeholder. Calling it must fail loudly
 * (per CONTRIBUTING.md "Engineering principles" §2 — no silent failures), with a message that
 * names the method and points the caller at the Phase 3 milestone in
 * the planned context-assembly phase.
 */
class HierarchicalAssembleTest {

    @Test
    @DisplayName(
            "throws UnsupportedOperationException whose message names " + "\"Hierarchical.assemble\" and \"Phase 3\"")
    void notYetImplemented() {
        var strategy = new Hierarchical(3);
        var doc = new ParsedDocument(
                "doc-1",
                List.of(new TextSection("anything", new SourceLocation(1, 1, 1, 1, 0))),
                new DocumentMetadata("test.pdf", 1, Optional.empty()));

        assertThatThrownBy(() -> strategy.assemble(doc))
                .isInstanceOf(UnsupportedOperationException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).contains("Hierarchical.assemble");
                    assertThat(ex.getMessage()).contains("planned context-assembly phase");
                });
    }
}
