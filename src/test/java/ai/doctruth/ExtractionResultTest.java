package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ExtractionResult}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code value} is non-null (T is generic; an inline {@link Person} record is used here).
 *   <li>{@code citations} is non-null (empty map is allowed).
 *   <li>{@code confidence} is non-null (empty map is allowed).
 *   <li>{@code provenance} is non-null.
 * </ul>
 *
 * <p>Defensive-copy contract: the citations and confidence maps must be defensively
 * copied on construction AND exposed as unmodifiable views, so mutation of either
 * the input map or the accessor's returned map cannot change the result's state.
 */
class ExtractionResultTest {

    /** A small concrete type to stand in for {@code T} in the parameterised tests. */
    record Person(String name, int age) {}

    private static Provenance sampleProvenance() {
        return new Provenance(
                "claude-sonnet-4-7",
                "20260301",
                Instant.parse("2026-05-07T05:30:00Z"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0);
    }

    private static Citation sampleCitation() {
        return new Citation(new SourceLocation(1, 1, 3, 3, 0), "Alex Chen", 0.97);
    }

    private static Confidence sampleConfidence() {
        return new Confidence(0.91, "exact match");
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a fully-populated result with one citation and one confidence")
        void typicalResult() {
            var person = new Person("Alex Chen", 30);
            var citations = Map.of("name", sampleCitation());
            var confidence = Map.of("name", sampleConfidence());
            var prov = sampleProvenance();

            var result = new ExtractionResult<>(person, citations, confidence, prov);

            assertThat(result.value()).isEqualTo(person);
            assertThat(result.citations()).containsEntry("name", sampleCitation());
            assertThat(result.confidence()).containsEntry("name", sampleConfidence());
            assertThat(result.provenance()).isEqualTo(prov);
        }

        @Test
        @DisplayName("accepts empty citations and confidence maps")
        void emptyMapsAllowed() {
            var person = new Person("Alex Chen", 30);

            var result = new ExtractionResult<>(person, Map.of(), Map.of(), sampleProvenance());

            assertThat(result.citations()).isEmpty();
            assertThat(result.confidence()).isEmpty();
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null value with NullPointerException")
        void nullValue() {
            assertThatThrownBy(() -> new ExtractionResult<Person>(null, Map.of(), Map.of(), sampleProvenance()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("value");
        }

        @Test
        @DisplayName("rejects null citations map with NullPointerException")
        void nullCitations() {
            var person = new Person("Alex Chen", 30);

            assertThatThrownBy(() -> new ExtractionResult<>(person, null, Map.of(), sampleProvenance()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("citations");
        }

        @Test
        @DisplayName("rejects null confidence map with NullPointerException")
        void nullConfidence() {
            var person = new Person("Alex Chen", 30);

            assertThatThrownBy(() -> new ExtractionResult<>(person, Map.of(), null, sampleProvenance()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("confidence");
        }

        @Test
        @DisplayName("rejects null provenance with NullPointerException")
        void nullProvenance() {
            var person = new Person("Alex Chen", 30);

            assertThatThrownBy(() -> new ExtractionResult<>(person, Map.of(), Map.of(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("provenance");
        }
    }

    @Nested
    @DisplayName("defensive copy")
    class DefensiveCopy {

        @Test
        @DisplayName("mutating the input citations map after construction does not mutate result.citations()")
        void inputCitationsMapIsCopied() {
            var person = new Person("Alex Chen", 30);
            Map<String, Citation> mutableCitations = new HashMap<>();
            mutableCitations.put("name", sampleCitation());

            var result = new ExtractionResult<>(person, mutableCitations, Map.of(), sampleProvenance());

            mutableCitations.put("age", sampleCitation());

            assertThat(result.citations()).hasSize(1).containsOnlyKeys("name");
        }

        @Test
        @DisplayName("mutating the input confidence map after construction does not mutate result.confidence()")
        void inputConfidenceMapIsCopied() {
            var person = new Person("Alex Chen", 30);
            Map<String, Confidence> mutableConfidence = new HashMap<>();
            mutableConfidence.put("name", sampleConfidence());

            var result = new ExtractionResult<>(person, Map.of(), mutableConfidence, sampleProvenance());

            mutableConfidence.put("age", sampleConfidence());

            assertThat(result.confidence()).hasSize(1).containsOnlyKeys("name");
        }

        @Test
        @DisplayName("result.citations() is unmodifiable — put() throws UnsupportedOperationException")
        void citationsAccessorIsUnmodifiable() {
            var person = new Person("Alex Chen", 30);
            var result = new ExtractionResult<>(person, Map.of("name", sampleCitation()), Map.of(), sampleProvenance());

            assertThat(result.citations()).isUnmodifiable();
            assertThatThrownBy(() -> result.citations().put("age", sampleCitation()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("result.confidence() is unmodifiable — put() throws UnsupportedOperationException")
        void confidenceAccessorIsUnmodifiable() {
            var person = new Person("Alex Chen", 30);
            var result =
                    new ExtractionResult<>(person, Map.of(), Map.of("name", sampleConfidence()), sampleProvenance());

            assertThat(result.confidence()).isUnmodifiable();
            assertThatThrownBy(() -> result.confidence().put("age", sampleConfidence()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
