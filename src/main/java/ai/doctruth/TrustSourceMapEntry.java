package ai.doctruth;

import java.util.List;
import java.util.Objects;

/**
 * Offset mapping from a rendered output range to a trust unit and evidence spans.
 *
 * @param startOffset     inclusive rendered text offset.
 * @param endOffset       exclusive rendered text offset.
 * @param unitId          trust unit id.
 * @param evidenceSpanIds evidence span ids backing this range.
 * @since 1.0.0
 */
public record TrustSourceMapEntry(int startOffset, int endOffset, String unitId, List<String> evidenceSpanIds) {

    public TrustSourceMapEntry {
        Objects.requireNonNull(unitId, "unitId");
        Objects.requireNonNull(evidenceSpanIds, "evidenceSpanIds");
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must be >= 0");
        }
        if (endOffset < startOffset) {
            throw new IllegalArgumentException("endOffset must be >= startOffset");
        }
        if (unitId.isBlank()) {
            throw new IllegalArgumentException("unitId must not be blank");
        }
        evidenceSpanIds = copyNonBlank(evidenceSpanIds);
    }

    private static List<String> copyNonBlank(List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            var value = Objects.requireNonNull(values.get(i), "evidenceSpanIds[" + i + "]");
            if (value.isBlank()) {
                throw new IllegalArgumentException("evidenceSpanIds must not contain blank values");
            }
        }
        return List.copyOf(values);
    }
}
