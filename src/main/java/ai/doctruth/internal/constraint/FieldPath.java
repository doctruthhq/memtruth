package ai.doctruth.internal.constraint;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

import ai.doctruth.ExtractionException;

final class FieldPath {

    private FieldPath() {}

    static Object read(Object target, String path, int retries) throws ExtractionException {
        Object current = target;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = readSegment(current, segment, path, retries);
        }
        return current;
    }

    private static Object readSegment(Object target, String segment, String path, int retries)
            throws ExtractionException {
        RecordComponent[] components = target.getClass().getRecordComponents();
        if (components == null) {
            throw failed("field path requires record target: " + path, retries);
        }
        for (RecordComponent component : components) {
            if (component.getName().equals(segment)) {
                return invoke(target, component, path, retries);
            }
        }
        throw failed("field path not found: " + path, retries);
    }

    private static Object invoke(Object target, RecordComponent component, String path, int retries)
            throws ExtractionException {
        Method accessor = component.getAccessor();
        try {
            accessor.setAccessible(true);
            return accessor.invoke(target);
        } catch (IllegalAccessException | InvocationTargetException | SecurityException e) {
            throw new ExtractionException(
                    "EXTRACTION_CONSTRAINT_FAILED",
                    "field path access failed at " + path + ": " + e.getMessage(),
                    retries,
                    e);
        }
    }

    private static ExtractionException failed(String message, int retries) {
        return new ExtractionException("EXTRACTION_CONSTRAINT_FAILED", message, retries);
    }
}
