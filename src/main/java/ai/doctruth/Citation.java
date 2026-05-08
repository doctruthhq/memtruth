package ai.doctruth;

import java.util.Objects;

/**
 * The verifiable evidence anchor for a single extracted field. A {@code Citation} ties an
 * extracted value back to a specific span of the source document plus the exact quote
 * recovered from that span and a confidence score for the page-attribution match itself.
 *
 * <p>Invariants (enforced by the compact constructor):
 *
 * <ul>
 *   <li>{@code location} is non-null.
 *   <li>{@code exactQuote} is non-null and non-blank.
 *   <li>{@code matchScore} is a real number in {@code [0.0, 1.0]} — {@code NaN} and infinities
 *       are rejected. {@code 1.0} means an exact substring match; lower values come from
 *       fuzzy / Levenshtein-style matchers.
 * </ul>
 *
 * @param location   the source-document span this citation points at.
 * @param exactQuote the literal text recovered from the source that justified the value.
 * @param matchScore similarity of {@code exactQuote} to the substring at {@code location};
 *                   downstream matchers should treat {@code matchScore < 0.85} as a warning.
 * @since 0.1.0
 */
public record Citation(SourceLocation location, String exactQuote, double matchScore) {

    public Citation {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(exactQuote, "exactQuote");
        if (exactQuote.isBlank()) {
            throw new IllegalArgumentException("exactQuote must not be blank");
        }
        if (Double.isNaN(matchScore) || matchScore < 0.0 || matchScore > 1.0) {
            throw new IllegalArgumentException("matchScore must be a real number in [0.0, 1.0], got " + matchScore);
        }
    }
}
