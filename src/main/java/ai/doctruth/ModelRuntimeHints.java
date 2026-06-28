package ai.doctruth;

import java.util.Objects;

record ModelRuntimeHints(String task, String backend, String format, String precision, String license) {

    ModelRuntimeHints {
        task = normalize(task);
        backend = normalize(backend);
        format = normalize(format);
        precision = normalize(precision);
        license = normalize(license);
    }

    static ModelRuntimeHints empty() {
        return new ModelRuntimeHints("", "", "", "", "");
    }

    boolean hasAny() {
        return !task.isBlank() || !backend.isBlank() || !format.isBlank() || !precision.isBlank() || !license.isBlank();
    }

    private static String normalize(String value) {
        Objects.requireNonNull(value, "value");
        return value.trim();
    }
}
