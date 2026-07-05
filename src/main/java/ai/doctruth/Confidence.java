package ai.doctruth;

import java.util.Objects;

/**
 * A confidence score for a single extracted field, plus a free-form rationale. Scores can come from
 * model output or DocTruth-owned evidence signals such as source quote matching.
 *
 * <p>Invariants (enforced by the compact constructor):
 *
 * <ul>
 *   <li>{@code score} is a real number in {@code [0.0, 1.0]} — {@code NaN} and infinities are rejected.
 *   <li>{@code rationale} is non-null. Empty string is allowed (some prompts skip rationale).
 * </ul>
 *
 * @param score     the confidence in {@code [0.0, 1.0]}.
 * @param rationale a free-form, possibly empty explanation of why the score was chosen.
 * @since 0.1.0
 */
public record Confidence(double score, String rationale) {

    public Confidence {
        Objects.requireNonNull(rationale, "rationale");
        if (Double.isNaN(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be a real number in [0.0, 1.0], got " + score);
        }
    }
}
