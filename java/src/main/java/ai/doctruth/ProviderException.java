package ai.doctruth;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Thrown by Layer 2 LLM providers (Anthropic, OpenAI, Gemini, DeepSeek) when an upstream call
 * fails. Carries the provider name, the HTTP status (when applicable), and a {@code retryable}
 * flag so the extraction loop can decide whether to back off or fail fast.
 *
 * <p>Checked.
 *
 * <p>Invariants:
 *
 * <ul>
 *   <li>{@code errorCode} non-null and non-blank.
 *   <li>{@code message} non-null.
 *   <li>{@code providerName} non-null and non-blank.
 *   <li>{@code httpStatus} is a non-null {@link OptionalInt}; pass {@link OptionalInt#empty()}
 *       (not {@code null}) for non-HTTP failures (timeout, DNS, schema-validation failure).
 * </ul>
 *
 * @since 0.1.0
 */
public class ProviderException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final String providerName;
    // OptionalInt is intentionally non-Serializable (per its Javadoc); we store the value +
    // presence flag so the enclosing Throwable's Serializable contract holds without leaning
    // on @SuppressWarnings("serial"). The accessor repackages into OptionalInt.
    private final int httpStatusValue;
    private final boolean httpStatusPresent;
    private final boolean retryable;

    public ProviderException(
            String errorCode, String message, String providerName, OptionalInt httpStatus, boolean retryable) {
        this(errorCode, message, providerName, httpStatus, retryable, null);
    }

    public ProviderException(
            String errorCode,
            String message,
            String providerName,
            OptionalInt httpStatus,
            boolean retryable,
            Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(providerName, "providerName");
        Objects.requireNonNull(httpStatus, "httpStatus");
        if (errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        if (providerName.isBlank()) {
            throw new IllegalArgumentException("providerName must not be blank");
        }
        this.errorCode = errorCode;
        this.providerName = providerName;
        this.httpStatusPresent = httpStatus.isPresent();
        this.httpStatusValue = this.httpStatusPresent ? httpStatus.getAsInt() : 0;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public String providerName() {
        return providerName;
    }

    public OptionalInt httpStatus() {
        return httpStatusPresent ? OptionalInt.of(httpStatusValue) : OptionalInt.empty();
    }

    public boolean retryable() {
        return retryable;
    }
}
