package ai.doctruth;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

final class JsonExtractionBuilderState {

    final boolean recordProvenance;
    final boolean recordBitemporal;
    final boolean recordConfidence;
    final int maxRetries;
    final ContextStrategy contextStrategy;
    final Instant sourcePublishedAt;
    final Set<String> requiredCitations;

    private JsonExtractionBuilderState(
            boolean recordProvenance,
            boolean recordBitemporal,
            boolean recordConfidence,
            int maxRetries,
            ContextStrategy contextStrategy,
            Instant sourcePublishedAt,
            Set<String> requiredCitations) {
        this.recordProvenance = recordProvenance;
        this.recordBitemporal = recordBitemporal;
        this.recordConfidence = recordConfidence;
        this.maxRetries = maxRetries;
        this.contextStrategy = contextStrategy;
        this.sourcePublishedAt = sourcePublishedAt;
        this.requiredCitations = Set.copyOf(requiredCitations);
    }

    static JsonExtractionBuilderState defaults() {
        return new JsonExtractionBuilderState(false, false, false, 0, null, null, Set.of());
    }

    JsonExtractionBuilderState withProvenance() {
        return copy(
                true,
                recordBitemporal,
                recordConfidence,
                maxRetries,
                contextStrategy,
                sourcePublishedAt,
                requiredCitations);
    }

    JsonExtractionBuilderState withBitemporal() {
        return copy(
                recordProvenance,
                true,
                recordConfidence,
                maxRetries,
                contextStrategy,
                sourcePublishedAt,
                requiredCitations);
    }

    JsonExtractionBuilderState withConfidence() {
        return copy(
                recordProvenance,
                recordBitemporal,
                true,
                maxRetries,
                contextStrategy,
                sourcePublishedAt,
                requiredCitations);
    }

    JsonExtractionBuilderState withMaxRetries(int retries) {
        if (retries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + retries);
        }
        return copy(
                recordProvenance,
                recordBitemporal,
                recordConfidence,
                retries,
                contextStrategy,
                sourcePublishedAt,
                requiredCitations);
    }

    JsonExtractionBuilderState withContextStrategy(ContextStrategy strategy) {
        return copy(
                recordProvenance,
                recordBitemporal,
                recordConfidence,
                maxRetries,
                Objects.requireNonNull(strategy, "contextStrategy"),
                sourcePublishedAt,
                requiredCitations);
    }

    JsonExtractionBuilderState withSourcePublishedAt(Instant publishedAt) {
        return copy(
                recordProvenance,
                recordBitemporal,
                recordConfidence,
                maxRetries,
                contextStrategy,
                Objects.requireNonNull(publishedAt, "sourcePublishedAt"),
                requiredCitations);
    }

    JsonExtractionBuilderState requireCitation(String fieldPath) {
        Objects.requireNonNull(fieldPath, "fieldPath");
        if (fieldPath.isBlank()) {
            throw new IllegalArgumentException("fieldPath must not be blank");
        }
        var next = new LinkedHashSet<>(requiredCitations);
        next.add(fieldPath);
        return copy(
                recordProvenance,
                recordBitemporal,
                recordConfidence,
                maxRetries,
                contextStrategy,
                sourcePublishedAt,
                next);
    }

    private JsonExtractionBuilderState copy(
            boolean provenance,
            boolean bitemporal,
            boolean confidence,
            int retries,
            ContextStrategy context,
            Instant publishedAt,
            Set<String> citations) {
        return new JsonExtractionBuilderState(
                provenance, bitemporal, confidence, retries, context, publishedAt, citations);
    }
}
