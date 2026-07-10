package ai.doctruth.internal.constraint;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import ai.doctruth.ExtractionException;

public final class ConstraintSet<T> {

    private static final ConstraintSet<?> EMPTY = new ConstraintSet<>(List.of(), List.of());

    private final List<FieldConstraint<T, ?>> fieldConstraints;
    private final List<ObjectConstraint<T>> objectConstraints;

    private ConstraintSet(List<FieldConstraint<T, ?>> fieldConstraints, List<ObjectConstraint<T>> objectConstraints) {
        this.fieldConstraints = List.copyOf(fieldConstraints);
        this.objectConstraints = List.copyOf(objectConstraints);
    }

    @SuppressWarnings("unchecked")
    public static <T> ConstraintSet<T> empty() {
        return (ConstraintSet<T>) EMPTY;
    }

    public <V> ConstraintSet<T> withField(
            String fieldPath, Class<V> valueType, Predicate<V> predicate, String message) {
        return new ConstraintSet<>(
                append(fieldConstraints, new FieldConstraint<>(fieldPath, valueType, predicate, message)),
                objectConstraints);
    }

    public ConstraintSet<T> withObject(Predicate<T> predicate, String message) {
        return new ConstraintSet<>(
                fieldConstraints, append(objectConstraints, new ObjectConstraint<>(predicate, message)));
    }

    public void validate(T value, int retries) throws ExtractionException {
        for (FieldConstraint<T, ?> constraint : fieldConstraints) {
            constraint.validate(value, retries);
        }
        for (ObjectConstraint<T> constraint : objectConstraints) {
            constraint.validate(value, retries);
        }
    }

    private static <E> List<E> append(List<E> values, E value) {
        return Stream.concat(values.stream(), Stream.of(value)).toList();
    }
}
