package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Behaviour tests for {@link SlidingWindow#assemble(ParsedDocument)}.
 *
 * <p>Behavioural contract pinned here (see class Javadoc on {@link SlidingWindow}):
 *
 * <ul>
 *   <li>Each section is rendered via the shared
 *       {@link ai.doctruth.internal.render.SectionRenderer}; rendered sections are joined by
 *       {@code "\n\n"} into a single string {@code full}.
 *   <li>If {@code full.length() <= windowChars}: return {@code full} verbatim — no truncation.
 *   <li>Otherwise: return {@code full.substring(0, windowChars)} — strict cap, never exceed.
 *   <li>Empty document (zero sections): returns {@code ""}.
 *   <li>{@code overlapChars} is reserved for v0.2.0+ multi-window iteration and is NOT used
 *       by the single-window {@code assemble} path.
 * </ul>
 *
 * <p>The "sliding" aspect lives in downstream multi-call orchestration; per-call
 * {@code assemble} returns ONE bounded prefix window. This is intentionally minimal: shipping
 * a complex multi-window assembler before we know how callers consume it would lock us in.
 */
class SlidingWindowAssembleTest {

    private static final SourceLocation ANYWHERE = new SourceLocation(1, 1, 1, 1, 0);
    private static final DocumentMetadata META = new DocumentMetadata("test.pdf", 1, Optional.empty());

    private static ParsedDocument doc(ParsedSection... sections) {
        return new ParsedDocument("doc-1", List.of(sections), META);
    }

    private static TextSection text(String s) {
        return new TextSection(s, ANYWHERE);
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName(
                "doc that fits within windowChars: returns full concatenation joined by " + "\"\\n\\n\", no truncation")
        void docThatFits() throws ExtractionException {
            var d = doc(text("alpha"), text("beta"), text("gamma"));
            var strategy = new SlidingWindow(10_000, 100);

            var assembled = strategy.assemble(d);

            assertThat(assembled).isEqualTo("alpha\n\nbeta\n\ngamma");
            assertThat(assembled.length()).isLessThanOrEqualTo(10_000);
        }

        @Test
        @DisplayName("multi-section document preserves the \"\\n\\n\" joiner between rendered "
                + "sections when within budget")
        void joinerPreservedWithinBudget() throws ExtractionException {
            var t = new TableSection(List.of(List.of("a", "b")), ANYWHERE);
            var f = new FigureSection("caption", ANYWHERE);
            var d = new ParsedDocument("doc-2", List.of(text("intro"), t, f), META);
            var strategy = new SlidingWindow(1_000, 0);

            assertThat(strategy.assemble(d)).isEqualTo("intro\n\na | b\n\n[Figure: caption]");
        }

        @Test
        @DisplayName("single-section doc that exactly fills windowChars: returned in full, no " + "truncation")
        void singleSectionExactlyFills() throws ExtractionException {
            var body = repeat('x', 50);
            var d = doc(text(body));
            var strategy = new SlidingWindow(50, 10);

            var assembled = strategy.assemble(d);

            assertThat(assembled).isEqualTo(body);
            assertThat(assembled).hasSize(50);
        }
    }

    @Nested
    @DisplayName("edge")
    class Edge {

        @Test
        @DisplayName("doc that overflows windowChars: returns first windowChars chars; tail of "
                + "full concatenation is dropped")
        void docOverflowsTakesPrefix() throws ExtractionException {
            var head = repeat('a', 30);
            var tail = repeat('b', 30);
            var d = doc(text(head), text(tail));
            // full = head + "\n\n" + tail = 30 + 2 + 30 = 62 chars.
            var strategy = new SlidingWindow(40, 5);

            var assembled = strategy.assemble(d);

            assertThat(assembled).hasSize(40);
            assertThat(assembled).isEqualTo(head + "\n\n" + repeat('b', 8));
            assertThat(assembled).doesNotContain(repeat('b', 30));
        }

        @Test
        @DisplayName("zero-section document returns the empty string")
        void zeroSectionsReturnsEmpty() throws ExtractionException {
            var d = doc();
            var strategy = new SlidingWindow(1_000, 100);

            assertThat(strategy.assemble(d)).isEmpty();
        }

        @Test
        @DisplayName(
                "single-section doc one-char over windowChars: result is exactly windowChars " + "long (strict cap)")
        void oneCharOverIsStrictlyCapped() throws ExtractionException {
            var body = repeat('z', 51);
            var d = doc(text(body));
            var strategy = new SlidingWindow(50, 0);

            var assembled = strategy.assemble(d);

            assertThat(assembled).hasSize(50);
            assertThat(assembled).isEqualTo(repeat('z', 50));
        }
    }

    @Nested
    @DisplayName("defensive")
    class Defensive {

        @Test
        @DisplayName("assemble(null) throws NullPointerException with \"doc\" in the message")
        void assembleNullThrows() {
            var strategy = new SlidingWindow(4_000, 500);

            assertThatThrownBy(() -> strategy.assemble(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("doc");
        }
    }

    private static String repeat(char c, int n) {
        var sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
