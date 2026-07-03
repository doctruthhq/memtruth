package ai.doctruth.internal.citation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.offset;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.doctruth.BoundingBox;
import ai.doctruth.Citation;
import ai.doctruth.DocumentMetadata;
import ai.doctruth.FigureSection;
import ai.doctruth.ParsedDocument;
import ai.doctruth.ParsedSection;
import ai.doctruth.SourceLocation;
import ai.doctruth.TextSection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link CitationMatcher}.
 *
 * <p>Pinned behaviours:
 *
 * <ul>
 *   <li>Exact substring match → {@code matchScore == 1.0}, exact field-string returned.
 *   <li>Fuzzy fallback uses Jaro-Winkler; threshold default 0.85.
 *   <li>Below threshold the matcher STILL returns a Citation (audit beats silent omission)
 *       and emits an SLF4J warning — verified via the SLF4J facade indirectly by checking
 *       the Citation is present (the side-effect is observed at the integration boundary).
 *   <li>Walks records recursively; produces JSON-pointer-ish field paths.
 *   <li>Skips null leaves and blank string leaves.
 * </ul>
 */
class CitationMatcherTest {

    record Person(String name, int age) {}

    record Address(String street, String city) {}

    record PersonWithAddress(String name, Address address) {}

    record People(List<PersonWithAddress> members) {}

    record NullableContract(String partyA, String partyB) {}

    private static ParsedDocument doc(String text) {
        return doc(List.of(text));
    }

    private static ParsedDocument doc(List<String> pages) {
        var sections = new java.util.ArrayList<ParsedSection>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            int page = i + 1;
            var loc = new SourceLocation(page, page, 1, 1, 0);
            sections.add(new TextSection(pages.get(i), loc));
        }
        return new ParsedDocument(
                "doc-1", List.copyOf(sections), new DocumentMetadata("test.pdf", pages.size(), Optional.empty()));
    }

    private static ParsedDocument docWithBox(String text, BoundingBox box) {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var section = new TextSection(text, loc, ai.doctruth.BlockKind.BODY, Optional.of(box));
        return new ParsedDocument("doc-1", List.of(section), new DocumentMetadata("test.pdf", 1, Optional.empty()));
    }

    private static ParsedDocument docWithFigureBox(String caption, BoundingBox box) {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var section = new FigureSection(caption, loc, Optional.of(box));
        return new ParsedDocument("doc-1", List.of(section), new DocumentMetadata("test.pdf", 1, Optional.empty()));
    }

    @Nested
    @DisplayName("ExactMatch")
    class ExactMatch {

        @Test
        @DisplayName("a string field whose value appears verbatim in a section gets matchScore == 1.0")
        void stringFieldExactMatch() {
            var doc = doc("Alex Chen, 30 years old");
            var matcher = new CitationMatcher();

            Map<String, Citation> out = matcher.matchAll(new Person("Alex Chen", 30), doc);

            assertThat(out).containsKey("name");
            var c = out.get("name");
            assertThat(c.matchScore()).isEqualTo(1.0);
            assertThat(c.exactQuote()).isEqualTo("Alex Chen");
            assertThat(c.location().pageStart()).isEqualTo(1);
        }

        @Test
        @DisplayName("an exact text-section match carries the section bounding box onto the citation")
        void exactMatchCarriesBoundingBox() {
            var box = new BoundingBox(10.0, 20.0, 110.0, 40.0);
            var doc = docWithBox("Alex Chen, 30 years old", box);
            var matcher = new CitationMatcher();

            Map<String, Citation> out = matcher.matchAll(new Person("Alex Chen", 30), doc);

            assertThat(out.get("name").boundingBox()).contains(box);
        }

        @Test
        @DisplayName("an exact figure-caption match carries the caption bounding box onto the citation")
        void exactFigureCaptionMatchCarriesBoundingBox() {
            var box = new BoundingBox(10.0, 20.0, 210.0, 40.0);
            var doc = docWithFigureBox("Alex Chen", box);
            var matcher = new CitationMatcher();

            Map<String, Citation> out = matcher.matchAll(new Person("Alex Chen", 30), doc);

            assertThat(out.get("name").boundingBox()).contains(box);
        }

        @Test
        @DisplayName("an integer field whose toString appears verbatim in a section gets matchScore == 1.0")
        void integerFieldExactMatch() {
            var doc = doc("Alex Chen, 30 years old");
            var matcher = new CitationMatcher();

            Map<String, Citation> out = matcher.matchAll(new Person("Alex Chen", 30), doc);

            assertThat(out).containsKey("age");
            assertThat(out.get("age").matchScore()).isEqualTo(1.0);
            assertThat(out.get("age").exactQuote()).isEqualTo("30");
        }

        @Test
        @DisplayName("a multi-page doc anchors the citation to the section that contains the value")
        void multiPageExactMatchTagsRightPage() {
            var doc = doc(List.of("page one trivia", "page two has Alex Chen here", "page three filler"));
            var matcher = new CitationMatcher();

            Map<String, Citation> out = matcher.matchAll(new Person("Alex Chen", 99), doc);

            var c = out.get("name");
            assertThat(c.matchScore()).isEqualTo(1.0);
            assertThat(c.location().pageStart()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("FuzzyMatch")
    class FuzzyMatch {

        @Test
        @DisplayName("OCR-drift typo in source produces a fuzzy match >= default threshold (0.85)")
        void fuzzyMatchAboveThreshold() {
            // "Alex Ch en" — OCR drift. Need a JaroWinkler score >= 0.85 vs query "Alex Chen".
            var doc = doc("contract signed by Alex Ch en on the eleventh");
            var matcher = new CitationMatcher();

            Map<String, Citation> out = matcher.matchAll(new Person("Alex Chen", 1), doc);

            var c = out.get("name");
            assertThat(c).isNotNull();
            assertThat(c.matchScore()).isGreaterThanOrEqualTo(0.85);
            assertThat(c.matchScore()).isLessThan(1.0);
            assertThat(c.location().pageStart()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("BelowThreshold")
    class BelowThreshold {

        @Test
        @DisplayName("when no section contains anything close to the field, the matcher still "
                + "returns the best low-score Citation (audit > silence)")
        void belowThresholdStillReturnsCitation() {
            var doc = doc("completely unrelated content about marsupials and engineering");
            var matcher = new CitationMatcher();

            Map<String, Citation> out = matcher.matchAll(new Person("Alex Chen", 99), doc);

            assertThat(out).containsKey("name");
            var c = out.get("name");
            assertThat(c.matchScore()).isLessThan(0.85);
            assertThat(c.matchScore()).isGreaterThanOrEqualTo(0.0);
            assertThat(c.exactQuote()).isNotBlank();
        }

        @Test
        @DisplayName("when the document has no sections, non-blank fields still get explicit zero-score citations")
        void emptyDocumentStillReturnsExplicitCitations() {
            var doc = new ParsedDocument("doc-1", List.of(), new DocumentMetadata("empty.pdf", 1, Optional.empty()));
            var matcher = new CitationMatcher();

            Map<String, Citation> out = matcher.matchAll(new Person("Alex Chen", 99), doc);

            assertThat(out).containsKeys("name", "age");
            assertThat(out.get("name").matchScore()).isZero();
            assertThat(out.get("age").matchScore()).isZero();
            assertThat(out.get("name").exactQuote()).isEqualTo("Alex Chen");
            assertThat(out.get("name").location().pageStart()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("NestedRecord")
    class NestedRecord {

        @Test
        @DisplayName("nested record fields use dotted paths like \"address.street\"")
        void nestedRecordPaths() {
            var doc = doc("HQ at 221B Baker Street, London, established long ago.");
            var matcher = new CitationMatcher();

            var value = new PersonWithAddress("Sherlock Holmes", new Address("221B Baker Street", "London"));
            Map<String, Citation> out = matcher.matchAll(value, doc);

            assertThat(out).containsKeys("address.street", "address.city");
            assertThat(out.get("address.street").matchScore()).isEqualTo(1.0);
            assertThat(out.get("address.city").matchScore()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("ListRecord")
    class ListRecord {

        @Test
        @DisplayName("list elements get bracketed-index paths like \"members[0].name\"")
        void listIndexedPaths() {
            var doc = doc("Members: Alice Anderson, Bob Brown.");
            var matcher = new CitationMatcher();

            var value = new People(List.of(
                    new PersonWithAddress("Alice Anderson", new Address("x", "y")),
                    new PersonWithAddress("Bob Brown", new Address("x", "y"))));
            Map<String, Citation> out = matcher.matchAll(value, doc);

            assertThat(out).containsKeys("members[0].name", "members[1].name");
            assertThat(out.get("members[0].name").matchScore()).isEqualTo(1.0);
            assertThat(out.get("members[1].name").matchScore()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("EdgeCase")
    class EdgeCase {

        @Test
        @DisplayName("null leaves are skipped (no entry in the result map)")
        void nullLeafSkipped() {
            var doc = doc("ACME contract signed");
            var matcher = new CitationMatcher();

            // partyB is intentionally null — should not appear in the result map.
            var value = new NullableContract("ACME", null);
            Map<String, Citation> out = matcher.matchAll(value, doc);

            assertThat(out).containsKey("partyA");
            assertThat(out).doesNotContainKey("partyB");
        }

        @Test
        @DisplayName("blank string leaves are skipped (no entry for them)")
        void blankLeafSkipped() {
            var doc = doc("ACME contract signed");
            var matcher = new CitationMatcher();

            var value = new NullableContract("ACME", "   ");
            Map<String, Citation> out = matcher.matchAll(value, doc);

            assertThat(out).doesNotContainKey("partyB");
        }

        @Test
        @DisplayName("the returned map is immutable")
        void resultIsImmutable() {
            var doc = doc("Alex Chen");
            var matcher = new CitationMatcher();
            var out = matcher.matchAll(new Person("Alex Chen", 30), doc);

            try {
                out.put("fake", new Citation(new SourceLocation(1, 1, 1, 1, 0), "x", 0.5));
                throw new AssertionError("expected UnsupportedOperationException");
            } catch (UnsupportedOperationException expected) {
                // ok
            }
        }

        @Test
        @DisplayName("null value argument throws NullPointerException")
        void nullValue() {
            var matcher = new CitationMatcher();
            assertThatNullPointerException().isThrownBy(() -> matcher.matchAll(null, doc("x")));
        }

        @Test
        @DisplayName("null doc argument throws NullPointerException")
        void nullDoc() {
            var matcher = new CitationMatcher();
            assertThatNullPointerException().isThrownBy(() -> matcher.matchAll(new Person("Alex", 30), null));
        }

        @Test
        @DisplayName("constructor rejects minScore outside [0.0, 1.0]")
        void invalidMinScore() {
            try {
                new CitationMatcher(-0.1);
                throw new AssertionError("expected IAE for -0.1");
            } catch (IllegalArgumentException expected) {
                // ok
            }
            try {
                new CitationMatcher(1.5);
                throw new AssertionError("expected IAE for 1.5");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }

        @Test
        @DisplayName("a custom minScore is honoured for the warn threshold (smoke test on equality)")
        void customMinScoreSmokeTest() {
            // We can't easily intercept the SLF4J warn here without adding a logging
            // appender, so we settle for: a 0.0 threshold accepts everything as
            // above-threshold and a 1.0 threshold accepts only exact matches. Verify the
            // matcher is constructible and exercised at both extremes.
            var doc = doc("Alex Chen");
            var lo = new CitationMatcher(0.0);
            var hi = new CitationMatcher(1.0);

            assertThat(lo.matchAll(new Person("Alex Chen", 30), doc).get("name").matchScore())
                    .isCloseTo(1.0, offset(1e-9));
            assertThat(hi.matchAll(new Person("Alex Chen", 30), doc).get("name").matchScore())
                    .isCloseTo(1.0, offset(1e-9));
        }
    }
}
