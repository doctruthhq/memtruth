package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the sealed interface {@link ParsedSection}.
 *
 * <p>{@code ParsedSection} is sealed and permits exactly three implementations:
 *
 * <ul>
 *   <li>{@link TextSection}
 *   <li>{@link TableSection}
 *   <li>{@link FigureSection}
 * </ul>
 *
 * <p>Compile-time safety: assigning a non-permitted implementation to a variable typed as
 * {@code ParsedSection} is a compile error. That property cannot be exercised at runtime; it
 * is verified by the Java compiler itself.
 */
class ParsedSectionTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName(
                "a switch over instanceof patterns is exhaustive across the three permitted subtypes without a default branch")
        void exhaustiveSwitchWithoutDefault() {
            var loc = new SourceLocation(1, 1, 1, 1, 0);
            List<ParsedSection> sections = List.of(
                    new TextSection("hello", loc),
                    new TableSection(List.of(List.of("a", "b")), loc),
                    new FigureSection("Figure 1", loc));

            for (var s : sections) {
                // Pattern-matching switch with no default — relies on the sealed contract.
                String label =
                        switch (s) {
                            case TextSection ignored -> "text";
                            case TableSection ignored -> "table";
                            case FigureSection ignored -> "figure";
                        };
                assertThat(label).isIn("text", "table", "figure");
            }
        }

        @Test
        @DisplayName("ParsedSection is a sealed interface")
        void isSealed() {
            assertThat(ParsedSection.class.isSealed()).isTrue();
            assertThat(ParsedSection.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("ParsedSection permits exactly three subtypes: TextSection, TableSection, FigureSection")
        void permitsExactlyThreeSubtypes() {
            var permitted = ParsedSection.class.getPermittedSubclasses();

            assertThat(permitted).hasSize(3);
            assertThat(permitted).containsExactlyInAnyOrder(TextSection.class, TableSection.class, FigureSection.class);
        }
    }
}
