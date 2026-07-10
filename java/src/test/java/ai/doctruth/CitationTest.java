package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link Citation}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code location} is non-null (NPE on null).
 *   <li>{@code exactQuote} is non-null and non-blank (IAE on empty / whitespace-only).
 *   <li>{@code matchScore} is a finite number in {@code [0.0, 1.0]} inclusive
 *       (NaN and infinities rejected).
 * </ul>
 */
class CitationTest {

    private static final BoundingBox BBOX = new BoundingBox(10.0, 20.0, 110.0, 40.0);

    private static SourceLocation sampleLocation() {
        return new SourceLocation(1, 1, 3, 3, 0);
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a citation with a valid location, non-blank quote, and mid-range score")
        void typicalCitation() {
            var loc = sampleLocation();
            var citation = new Citation(loc, "Acme Corp Ltd", 0.97);

            assertThat(citation.location()).isSameAs(loc);
            assertThat(citation.exactQuote()).isEqualTo("Acme Corp Ltd");
            assertThat(citation.matchScore()).isEqualTo(0.97);
            assertThat(citation.boundingBox()).isEmpty();
        }

        @Test
        @DisplayName("four-arg constructor retains a page-normalized bounding box")
        void fourArgRetainsBoundingBox() {
            var citation = new Citation(sampleLocation(), "Acme Corp Ltd", 0.97, Optional.of(BBOX));

            assertThat(citation.boundingBox()).contains(BBOX);
        }

        @Test
        @DisplayName("accepts matchScore boundary 0.0 (no fuzzy match, but cited)")
        void matchScoreLowerBoundary() {
            var citation = new Citation(sampleLocation(), "x", 0.0);

            assertThat(citation.matchScore()).isZero();
        }

        @Test
        @DisplayName("accepts matchScore boundary 1.0 (perfect match)")
        void matchScoreUpperBoundary() {
            var citation = new Citation(sampleLocation(), "x", 1.0);

            assertThat(citation.matchScore()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("two citations with equal fields are equal (record semantics)")
        void recordEquality() {
            var loc = sampleLocation();
            var a = new Citation(loc, "Acme Corp Ltd", 0.97);
            var b = new Citation(loc, "Acme Corp Ltd", 0.97);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null location with NullPointerException")
        void nullLocation() {
            assertThatThrownBy(() -> new Citation(null, "x", 1.0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("location");
        }

        @Test
        @DisplayName("rejects null exactQuote with NullPointerException")
        void nullExactQuote() {
            assertThatThrownBy(() -> new Citation(sampleLocation(), null, 1.0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("exactQuote");
        }

        @Test
        @DisplayName("rejects null boundingBox optional")
        void nullBoundingBoxOptional() {
            assertThatThrownBy(() -> new Citation(sampleLocation(), "x", 1.0, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("boundingBox");
        }

        @Test
        @DisplayName("rejects empty exactQuote with IllegalArgumentException")
        void emptyExactQuote() {
            assertThatThrownBy(() -> new Citation(sampleLocation(), "", 1.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactQuote");
        }

        @Test
        @DisplayName("rejects whitespace-only exactQuote with IllegalArgumentException")
        void blankExactQuote() {
            assertThatThrownBy(() -> new Citation(sampleLocation(), "   ", 1.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactQuote");
        }

        @Test
        @DisplayName("rejects matchScore just below 0.0 with IllegalArgumentException")
        void matchScoreBelowZero() {
            assertThatThrownBy(() -> new Citation(sampleLocation(), "x", -0.0001))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("matchScore");
        }

        @Test
        @DisplayName("rejects matchScore just above 1.0 with IllegalArgumentException")
        void matchScoreAboveOne() {
            assertThatThrownBy(() -> new Citation(sampleLocation(), "x", 1.0001))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("matchScore");
        }

        @Test
        @DisplayName("rejects matchScore NaN with IllegalArgumentException")
        void matchScoreNaN() {
            assertThatThrownBy(() -> new Citation(sampleLocation(), "x", Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("matchScore");
        }

        @Test
        @DisplayName("rejects matchScore positive infinity with IllegalArgumentException")
        void matchScorePositiveInfinity() {
            assertThatThrownBy(() -> new Citation(sampleLocation(), "x", Double.POSITIVE_INFINITY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("matchScore");
        }
    }
}
