package ai.doctruth.internal.constraint;

import java.util.Objects;
import java.util.function.Predicate;

import ai.doctruth.ExtractionException;

record FieldConstraint<T, V>(String path, Class<V> valueType, Predicate<V> predicate, String message) {

    FieldConstraint {
        requireText(path, "fieldPath");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(predicate, "predicate");
        requireText(message, "message");
    }

    void validate(T target, int retries) throws ExtractionException {
        Object raw = FieldPath.read(target, path, retries);
        if (raw != null && !valueType.isInstance(raw)) {
            throw failure(
                    "expected " + valueType.getName() + " but got "
                            + raw.getClass().getName(),
                    retries);
        }
        V value = valueType.cast(raw);
        if (!matches(value, retries)) {
            throw failure(message, retries);
        }
    }

    private boolean matches(V value, int retries) throws ExtractionException {
        try {
            return predicate.test(value);
        } catch (RuntimeException e) {
            throw new ExtractionException(
                    "EXTRACTION_CONSTRAINT_FAILED",
                    "constraint evaluation failed at " + path + ": " + e.getMessage(),
                    retries,
                    e);
        }
    }

    private ExtractionException failure(String reason, int retries) {
        return new ExtractionException(
                "EXTRACTION_CONSTRAINT_FAILED", "constraint failed at " + path + ": " + reason, retries);
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
