package ai.doctruth;

import java.util.Objects;

/**
 * Structured parser uncertainty or fallback signal.
 *
 * @param code     stable machine-readable warning code.
 * @param severity warning severity.
 * @param message  human-readable context, possibly empty.
 * @since 1.0.0
 */
public record ParserWarning(String code, ParserWarningSeverity severity, String message) {

    public ParserWarning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }
}
