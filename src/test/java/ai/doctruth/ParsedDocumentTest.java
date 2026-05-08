package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ParsedDocument}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code docId} is non-null and non-blank.
 *   <li>{@code sections} is non-null. An empty list IS allowed (e.g. a fully-blank PDF still
 *       returns a {@code ParsedDocument}; downstream code is responsible for handling it).
 *   <li>{@code metadata} is non-null.
 * </ul>
 *
 * <p>Defensive-copy contract: the record stores an unmodifiable copy of the sections list.
 */
class ParsedDocumentTest {

    private static final SourceLocation LOC = new SourceLocation(1, 1, 1, 1, 0);
    private static final DocumentMetadata META = new DocumentMetadata("tender.pdf", 10, Optional.empty());

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a docId, an empty sections list, and metadata")
        void emptySectionsAllowed() {
            var doc = new ParsedDocument("doc-1", List.of(), META);

            assertThat(doc.docId()).isEqualTo("doc-1");
            assertThat(doc.sections()).isEmpty();
            assertThat(doc.metadata()).isEqualTo(META);
        }

        @Test
        @DisplayName("accepts one TextSection, one TableSection, and one FigureSection (sealed-interface acceptance)")
        void heterogeneousSections() {
            var text = new TextSection("intro", LOC);
            var table = new TableSection(List.of(List.of("h1", "h2")), LOC);
            var figure = new FigureSection("Figure 1", LOC);

            var doc = new ParsedDocument("doc-2", List.<ParsedSection>of(text, table, figure), META);

            assertThat(doc.sections()).hasSize(3);
            assertThat(doc.sections()).containsExactly(text, table, figure);
        }

        @Test
        @DisplayName("two ParsedDocuments with equal fields are equal (record semantics)")
        void recordEquality() {
            var a = new ParsedDocument("doc-3", List.of(), META);
            var b = new ParsedDocument("doc-3", List.of(), META);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null docId with NullPointerException")
        void nullDocId() {
            assertThatThrownBy(() -> new ParsedDocument(null, List.of(), META))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("docId");
        }

        @Test
        @DisplayName("rejects an empty docId with IllegalArgumentException")
        void emptyDocId() {
            assertThatThrownBy(() -> new ParsedDocument("", List.of(), META))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("docId");
        }

        @Test
        @DisplayName("rejects a whitespace-only docId with IllegalArgumentException")
        void blankDocId() {
            assertThatThrownBy(() -> new ParsedDocument("   ", List.of(), META))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("docId");
        }

        @Test
        @DisplayName("rejects null sections with NullPointerException")
        void nullSections() {
            assertThatThrownBy(() -> new ParsedDocument("doc-1", null, META))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sections");
        }

        @Test
        @DisplayName("rejects null metadata with NullPointerException")
        void nullMetadata() {
            assertThatThrownBy(() -> new ParsedDocument("doc-1", List.of(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metadata");
        }
    }

    @Nested
    @DisplayName("defensive copy")
    class DefensiveCopy {

        @Test
        @DisplayName("mutating the input sections list after construction does not affect the stored sections")
        void mutationOfInputDoesNotLeak() {
            var sections = new ArrayList<ParsedSection>();
            sections.add(new TextSection("intro", LOC));

            var doc = new ParsedDocument("doc-1", sections, META);
            sections.add(new FigureSection("Figure 1", LOC));

            assertThat(doc.sections()).hasSize(1);
            assertThat(doc.sections().get(0)).isInstanceOf(TextSection.class);
        }

        @Test
        @DisplayName("calling sections().add(...) throws UnsupportedOperationException")
        void sectionsListIsUnmodifiable() {
            var doc = new ParsedDocument(
                    "doc-1", new ArrayList<>(List.<ParsedSection>of(new TextSection("intro", LOC))), META);

            assertThatThrownBy(() -> doc.sections().add(new FigureSection("Figure 1", LOC)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
