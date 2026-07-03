package ai.doctruth;

import java.util.List;
import java.util.Objects;

/**
 * LLM/RAG chunk that preserves the units and evidence spans it came from.
 *
 * @param chunkId         stable chunk id.
 * @param text            rendered chunk text.
 * @param unitIds         trust units included in this chunk.
 * @param evidenceSpanIds evidence spans included in this chunk.
 * @since 1.0.0
 */
public record TrustDocumentChunk(String chunkId, String text, List<String> unitIds, List<String> evidenceSpanIds) {

    public TrustDocumentChunk {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(unitIds, "unitIds");
        Objects.requireNonNull(evidenceSpanIds, "evidenceSpanIds");
        if (chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId must not be blank");
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        unitIds = copyNonBlank(unitIds, "unitIds");
        evidenceSpanIds = copyNonBlank(evidenceSpanIds, "evidenceSpanIds");
    }

    private static List<String> copyNonBlank(List<String> values, String name) {
        for (int i = 0; i < values.size(); i++) {
            var value = Objects.requireNonNull(values.get(i), name + "[" + i + "]");
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain blank values");
            }
        }
        return List.copyOf(values);
    }
}
