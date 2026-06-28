package ai.doctruth;

import java.util.List;
import java.util.Objects;

/**
 * Evidence links for a {@link TrustUnit}.
 *
 * @param evidenceSpanIds evidence span ids supported by this unit.
 * @param confidence      unit confidence.
 * @param warnings        unit-local parser warnings.
 * @since 1.0.0
 */
public record TrustUnitEvidence(List<String> evidenceSpanIds, Confidence confidence, List<ParserWarning> warnings) {

    public TrustUnitEvidence {
        Objects.requireNonNull(evidenceSpanIds, "evidenceSpanIds");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(warnings, "warnings");
        evidenceSpanIds = copyNonBlankStrings(evidenceSpanIds);
        warnings = List.copyOf(warnings);
    }

    private static List<String> copyNonBlankStrings(List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            var value = Objects.requireNonNull(values.get(i), "evidenceSpanIds[" + i + "]");
            if (value.isBlank()) {
                throw new IllegalArgumentException("evidenceSpanIds must not contain blank values");
            }
        }
        return List.copyOf(values);
    }
}
