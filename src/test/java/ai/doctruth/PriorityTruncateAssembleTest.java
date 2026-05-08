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
 * Behaviour tests for {@link PriorityTruncate#assemble(ParsedDocument)}.
 *
 * <p>Behavioural contract pinned here to prevent silent context-budget overruns; CONTRIBUTING.md §2
 * "no silent failures" exists to make this explicit:
 *
 * <ul>
 *   <li>Each section is rendered via the shared
 *       {@link ai.doctruth.internal.render.SectionRenderer} ({@code TextSection} → raw text,
 *       {@code TableSection} → {@code " | "}-joined rows, {@code FigureSection} →
 *       {@code "[Figure: <caption>]"}).
 *   <li>A section is "priority" iff its rendered text contains, case-insensitively, any of
 *       {@code prioritySectionPatterns()}. Substring match — keep the layer dumb; users own
 *       the patterns.
 *   <li>If the total rendered length (joined by {@code "\n\n"}) is &le; {@code maxChars()},
 *       return all sections joined.
 *   <li>If &gt; budget but priority-only fits, include all priority sections in document
 *       order plus as-many-non-priority-as-fit, scanning in document order, joined by
 *       {@code "\n\n"} — and the result is &le; {@code maxChars()}.
 *   <li>If priority-only &gt; budget:
 *       <ul>
 *         <li>{@link OverBudgetPolicy#STRICT} → throw {@link ExtractionException} with
 *             {@code errorCode == "CONTEXT_OVER_BUDGET"}.
 *         <li>{@link OverBudgetPolicy#WARN_AND_INCLUDE} → SLF4J {@code warn}, return all
 *             priority sections (length may exceed budget; documented behaviour).
 *       </ul>
 *   <li>An empty {@code prioritySectionPatterns} list means "no priorities — pure character
 *       budget truncation in document order".
 *   <li>A document with zero sections returns the empty string regardless of patterns.
 *   <li>Pattern matching is case-insensitive.
 * </ul>
 *
 * <p><b>Note on log assertions:</b> the WARN_AND_INCLUDE path emits a SLF4J {@code warn}.
 * These tests verify the structural return-value behaviour and trust the log statement
 * (capturing a SLF4J event in-test would require pulling in a logback / log4j2 testing
 * appender, which CONTRIBUTING.md "Build, don't synthesize" + ADR 0003 "no extra deps" both push
 * back on for a single-line warning). The grep-style structural check on the source file
 * is left out as the brief allows — return-value behaviour is the load-bearing contract.
 */
class PriorityTruncateAssembleTest {

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
        @DisplayName("doc that fits within maxChars: returns all sections joined by \"\\n\\n\" "
                + "and result length <= maxChars")
        void docThatFits() throws ExtractionException {
            var d = doc(text("Qualifications: ABC123"), text("Random filler"));
            var strategy = new PriorityTruncate(List.of("Qualifications"), 10_000, OverBudgetPolicy.STRICT);

            var assembled = strategy.assemble(d);

            assertThat(assembled).isEqualTo("Qualifications: ABC123\n\nRandom filler");
            assertThat(assembled.length()).isLessThanOrEqualTo(10_000);
        }

        @Test
        @DisplayName("single-section doc that fits: returned exactly with no joiner overhead")
        void singleSectionFits() throws ExtractionException {
            var d = doc(text("only one"));
            var strategy = new PriorityTruncate(List.of(), 100, OverBudgetPolicy.STRICT);

            assertThat(strategy.assemble(d)).isEqualTo("only one");
        }
    }

    @Nested
    @DisplayName("over-budget — both priority and filler exist")
    class OverBudgetMixed {

        @Test
        @DisplayName("priority fits but filler overflows: result includes priority + as-many-"
                + "filler-as-fit, all in document order, total <= maxChars")
        void priorityFitsFillerTrimmed() throws ExtractionException {
            // priority pattern matches the second section ("Qualifications").
            // sections: F1(20 char), Q(40 char), F2(50 char) → total > budget.
            var f1 = text(repeat('a', 20));
            var q = text("Qualifications " + repeat('q', 24)); // 40 chars total
            var f2 = text(repeat('b', 50));
            var d = doc(f1, q, f2);
            // budget tight: must include Q (40) + at least one filler; pick budget that
            // admits f1 but not f2. Joiners are 2 chars ("\n\n").
            // f1 + "\n\n" + q = 20 + 2 + 40 = 62.
            // adding "\n\n" + f2 would be 62 + 2 + 50 = 114.
            int budget = 80; // accepts f1 + q (62) but rejects adding f2 (114).
            var strategy = new PriorityTruncate(List.of("Qualifications"), budget, OverBudgetPolicy.STRICT);

            var assembled = strategy.assemble(d);

            assertThat(assembled).contains(q.text());
            assertThat(assembled).contains(f1.text());
            assertThat(assembled).doesNotContain(f2.text());
            assertThat(assembled.length()).isLessThanOrEqualTo(budget);
        }

        @Test
        @DisplayName("priority alone fits but adding ANY filler would push over: filler entirely "
                + "trimmed; result == priority sections joined by \"\\n\\n\"")
        void priorityFitsButFillerWouldOverflow() throws ExtractionException {
            var f1 = text(repeat('a', 100));
            var q = text("Qualifications block"); // 20 chars
            var d = doc(f1, q);
            // priority alone: just q (20). adding f1 would need 100 + 2 + 20 = 122.
            // budget = 50: priority fits, no filler can be added without overflow.
            var strategy = new PriorityTruncate(List.of("Qualifications"), 50, OverBudgetPolicy.STRICT);

            var assembled = strategy.assemble(d);

            assertThat(assembled).isEqualTo("Qualifications block");
            assertThat(assembled.length()).isLessThanOrEqualTo(50);
        }

        @Test
        @DisplayName("priority sections appear in document order, not pattern order, when " + "interleaved with filler")
        void priorityPreservesDocumentOrder() throws ExtractionException {
            var q1 = text("Qualifications first");
            var f = text(repeat('z', 200));
            var s = text("Scoring criteria second");
            var d = doc(q1, f, s);
            // budget: includes both priority sections but not the filler.
            // q1 + "\n\n" + s = 20 + 2 + 23 = 45.
            // adding filler anywhere would need + 2 + 200 = 247.
            int budget = 100;
            var strategy = new PriorityTruncate(List.of("Qualifications", "Scoring"), budget, OverBudgetPolicy.STRICT);

            var assembled = strategy.assemble(d);

            assertThat(assembled).isEqualTo("Qualifications first\n\nScoring criteria second");
        }
    }

    @Nested
    @DisplayName("over-budget — priority alone exceeds maxChars")
    class OverBudgetPriorityAlone {

        @Test
        @DisplayName("STRICT + priority-only > maxChars: throws ExtractionException with "
                + "errorCode == \"CONTEXT_OVER_BUDGET\"")
        void strictThrowsOverBudget() {
            var q1 = text("Qualifications " + repeat('a', 100));
            var q2 = text("Qualifications " + repeat('b', 100));
            var d = doc(q1, q2);
            var strategy = new PriorityTruncate(List.of("Qualifications"), 50, OverBudgetPolicy.STRICT);

            assertThatThrownBy(() -> strategy.assemble(d))
                    .isInstanceOf(ExtractionException.class)
                    .satisfies(ex -> {
                        var ee = (ExtractionException) ex;
                        assertThat(ee.errorCode()).isEqualTo("CONTEXT_OVER_BUDGET");
                    });
        }

        @Test
        @DisplayName("WARN_AND_INCLUDE + priority-only > maxChars: returns ALL priority sections "
                + "joined; result length > maxChars (documented behaviour, not a bug)")
        void warnAndIncludeReturnsAllPriority() throws ExtractionException {
            var q1 = text("Qualifications " + repeat('a', 100)); // 115 chars
            var q2 = text("Qualifications " + repeat('b', 100)); // 115 chars
            var f = text(repeat('z', 30));
            var d = doc(q1, f, q2);
            var strategy = new PriorityTruncate(List.of("Qualifications"), 50, OverBudgetPolicy.WARN_AND_INCLUDE);

            var assembled = strategy.assemble(d);

            assertThat(assembled).contains(q1.text());
            assertThat(assembled).contains(q2.text());
            assertThat(assembled).doesNotContain(f.text());
            // documented: result MAY exceed maxChars in WARN_AND_INCLUDE mode.
            assertThat(assembled.length()).isGreaterThan(50);
        }
    }

    @Nested
    @DisplayName("edge")
    class Edge {

        @Test
        @DisplayName("empty prioritySectionPatterns: every section is filler — pure budget truncation in doc order")
        void emptyPatternsIsPureBudgetTruncation() throws ExtractionException {
            var s1 = text(repeat('a', 30));
            var s2 = text(repeat('b', 30));
            var s3 = text(repeat('c', 30));
            var d = doc(s1, s2, s3);
            // s1 + "\n\n" + s2 = 62. adding s3 would be 62 + 2 + 30 = 94.
            int budget = 80; // accepts s1+s2 only.
            var strategy = new PriorityTruncate(List.of(), budget, OverBudgetPolicy.STRICT);

            var assembled = strategy.assemble(d);

            assertThat(assembled).isEqualTo(s1.text() + "\n\n" + s2.text());
            assertThat(assembled.length()).isLessThanOrEqualTo(budget);
        }

        @Test
        @DisplayName("zero-section document returns the empty string regardless of patterns")
        void zeroSectionsReturnsEmpty() throws ExtractionException {
            var d = doc();
            var strategy = new PriorityTruncate(List.of("Qualifications"), 25_000, OverBudgetPolicy.STRICT);

            assertThat(strategy.assemble(d)).isEmpty();
        }

        @Test
        @DisplayName("pattern matching is case-insensitive: a section text containing "
                + "\"qualifications\" matches a pattern \"Qualifications\"")
        void caseInsensitivePatternMatch() throws ExtractionException {
            var lower = text("the qualifications block here");
            var filler = text(repeat('z', 200));
            var d = doc(filler, lower);
            // budget: only the priority section fits.
            // lower len = 29. filler len = 200. lower + "\n\n" + filler = 231.
            int budget = 50;
            var strategy = new PriorityTruncate(List.of("Qualifications"), budget, OverBudgetPolicy.STRICT);

            var assembled = strategy.assemble(d);

            assertThat(assembled).isEqualTo("the qualifications block here");
        }

        @Test
        @DisplayName("table and figure sections render via SectionRenderer and participate in "
                + "priority classification by their rendered text")
        void tableAndFigureRenderingParticipate() throws ExtractionException {
            var table = new TableSection(List.of(List.of("Qualifications", "ABC")), ANYWHERE);
            var figure = new FigureSection("noise", ANYWHERE);
            var d = new ParsedDocument("doc-2", List.of(table, figure), META);
            var strategy = new PriorityTruncate(List.of("Qualifications"), 1_000, OverBudgetPolicy.STRICT);

            var assembled = strategy.assemble(d);

            // Both fit, so both included.
            assertThat(assembled).isEqualTo("Qualifications | ABC\n\n[Figure: noise]");
        }
    }

    @Nested
    @DisplayName("defensive")
    class Defensive {

        @Test
        @DisplayName("assemble(null) throws NullPointerException")
        void assembleNullThrows() {
            var strategy = new PriorityTruncate(List.of("Qualifications"), 1_000, OverBudgetPolicy.STRICT);

            assertThatThrownBy(() -> strategy.assemble(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("repeated calls on the same strategy are idempotent and pure")
        void idempotent() throws ExtractionException {
            var d = doc(text("Qualifications keep"), text("filler"));
            var strategy = new PriorityTruncate(List.of("Qualifications"), 10_000, OverBudgetPolicy.STRICT);

            assertThat(strategy.assemble(d)).isEqualTo(strategy.assemble(d));
        }

        @Test
        @DisplayName("input section list passed through ParsedDocument is not mutated")
        void doesNotMutateInputs() throws ExtractionException {
            var sections = new ArrayList<ParsedSection>();
            sections.add(text("Qualifications block"));
            sections.add(text(repeat('a', 50)));
            var d = new ParsedDocument("doc-3", sections, META);
            int sizeBefore = d.sections().size();
            var strategy = new PriorityTruncate(List.of("Qualifications"), 30, OverBudgetPolicy.STRICT);

            strategy.assemble(d);

            assertThat(d.sections()).hasSize(sizeBefore);
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
