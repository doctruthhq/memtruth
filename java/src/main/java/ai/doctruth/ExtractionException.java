package ai.doctruth;

import java.util.Objects;

/**
 * Thrown from the public extraction API when an extraction run fails after exhausting retries
 * or when an invariant is violated mid-flight. Carries a stable {@code errorCode} suitable
 * for log scraping plus the retry count at the moment of failure (helps distinguish
 * transient provider flakes from systematic schema errors).
 *
 * <p>Checked deliberately — auditable libraries force callers to write the {@code catch}.
 *
 * <p>Invariants:
 *
 * <ul>
 *   <li>{@code errorCode} non-null and non-blank.
 *   <li>{@code message} non-null.
 *   <li>{@code retries >= 0}.
 * </ul>
 *
 * @since 0.1.0
 */
public class ExtractionException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final int retries;

    public ExtractionException(String errorCode, String message, int retries) {
        this(errorCode, message, retries, null);
    }

    public ExtractionException(String errorCode, String message, int retries, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        Objects.requireNonNull(errorCode, "errorCode");
        if (errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        if (retries < 0) {
            throw new IllegalArgumentException("retries must be >= 0, got " + retries);
        }
        this.errorCode = errorCode;
        this.retries = retries;
    }

    public String errorCode() {
        return errorCode;
    }

    public int retries() {
        return retries;
    }
}
