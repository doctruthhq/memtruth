package ai.doctruth;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Predicate;

import ai.doctruth.internal.constraint.ConstraintSet;
import ai.doctruth.spi.AuditEventListener;

final class ExtractionBuilderState<T> {

    final boolean recordProvenance;
    final boolean recordBitemporal;
    final boolean recordConfidence;
    final int maxRetries;
    final ContextStrategy contextStrategy;
    final Instant sourcePublishedAt;
    final AuditEventListener auditListener;
    final ConstraintSet<T> constraints;

    private ExtractionBuilderState(
            boolean recordProvenance,
            boolean recordBitemporal,
            boolean recordConfidence,
            int maxRetries,
            ContextStrategy contextStrategy,
            Instant sourcePublishedAt,
            AuditEventListener auditListener,
            ConstraintSet<T> constraints) {
        this.recordProvenance = recordProvenance;
        this.recordBitemporal = recordBitemporal;
        this.recordConfidence = recordConfidence;
        this.maxRetries = maxRetries;
        this.contextStrategy = contextStrategy;
        this.sourcePublishedAt = sourcePublishedAt;
        this.auditListener = auditListener;
        this.constraints = constraints;
    }

    static <T> ExtractionBuilderState<T> defaults() {
        return new ExtractionBuilderState<>(
                false, false, false, 0, null, null, AuditEventListener.NOOP, ConstraintSet.empty());
    }

    ExtractionBuilderState<T> withProvenance() {
        return copy(
                true,
                recordBitemporal,
                recordConfidence,
                maxRetries,
                contextStrategy,
                sourcePublishedAt,
                auditListener,
                constraints);
    }

    ExtractionBuilderState<T> withBitemporal() {
        return copy(
                recordProvenance,
                true,
                recordConfidence,
                maxRetries,
                contextStrategy,
                sourcePublishedAt,
                auditListener,
                constraints);
    }

    ExtractionBuilderState<T> withConfidence() {
        return copy(
                recordProvenance,
                recordBitemporal,
                true,
                maxRetries,
                contextStrategy,
                sourcePublishedAt,
                auditListener,
                constraints);
    }

    ExtractionBuilderState<T> withMaxRetries(int nextRetries) {
        if (nextRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + nextRetries);
        }
        return copy(
                recordProvenance,
                recordBitemporal,
                recordConfidence,
                nextRetries,
                contextStrategy,
                sourcePublishedAt,
                auditListener,
                constraints);
    }

    ExtractionBuilderState<T> withContextStrategy(ContextStrategy nextStrategy) {
        Objects.requireNonNull(nextStrategy, "contextStrategy");
        return copy(
                recordProvenance,
                recordBitemporal,
                recordConfidence,
                maxRetries,
                nextStrategy,
                sourcePublishedAt,
                auditListener,
                constraints);
    }

    ExtractionBuilderState<T> withSourcePublishedAt(Instant nextPublishedAt) {
        Objects.requireNonNull(nextPublishedAt, "sourcePublishedAt");
        return copy(
                recordProvenance,
                recordBitemporal,
                recordConfidence,
                maxRetries,
                contextStrategy,
                nextPublishedAt,
                auditListener,
                constraints);
    }

    <V> ExtractionBuilderState<T> withFieldConstraint(
            String fieldPath, Class<V> valueType, Predicate<V> predicate, String message) {
        return copy(
                recordProvenance,
                recordBitemporal,
                recordConfidence,
                maxRetries,
                contextStrategy,
                sourcePublishedAt,
                auditListener,
                constraints.withField(fieldPath, valueType, predicate, message));
    }

    ExtractionBuilderState<T> withObjectConstraint(Predicate<T> predicate, String message) {
        return copy(
                recordProvenance,
                recordBitemporal,
                recordConfidence,
                maxRetries,
                contextStrategy,
                sourcePublishedAt,
                auditListener,
                constraints.withObject(predicate, message));
    }

    ExtractionBuilderState<T> withAuditListener(AuditEventListener nextListener) {
        Objects.requireNonNull(nextListener, "listener");
        return copy(
                recordProvenance,
                recordBitemporal,
                recordConfidence,
                maxRetries,
                contextStrategy,
                sourcePublishedAt,
                nextListener,
                constraints);
    }

    private ExtractionBuilderState<T> copy(
            boolean nextProvenance,
            boolean nextBitemporal,
            boolean nextConfidence,
            int nextRetries,
            ContextStrategy nextContext,
            Instant nextPublishedAt,
            AuditEventListener nextListener,
            ConstraintSet<T> nextConstraints) {
        return new ExtractionBuilderState<>(
                nextProvenance,
                nextBitemporal,
                nextConfidence,
                nextRetries,
                nextContext,
                nextPublishedAt,
                nextListener,
                nextConstraints);
    }
}
