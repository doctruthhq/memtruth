package ai.doctruth.internal.constraint;

import java.util.Objects;
import java.util.function.Predicate;

import ai.doctruth.ExtractionException;

record ObjectConstraint<T>(Predicate<T> predicate, String message) {

    ObjectConstraint {
        Objects.requireNonNull(predicate, "predicate");
        requireText(message, "message");
    }

    void validate(T value, int retries) throws ExtractionException {
        if (!matches(value, retries)) {
            throw new ExtractionException(
                    "EXTRACTION_CONSTRAINT_FAILED", "constraint failed at $: " + message, retries);
        }
    }

    private boolean matches(T value, int retries) throws ExtractionException {
        try {
            return predicate.test(value);
        } catch (RuntimeException e) {
            throw new ExtractionException(
                    "EXTRACTION_CONSTRAINT_FAILED", "constraint evaluation failed at $: " + e.getMessage(), retries, e);
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
