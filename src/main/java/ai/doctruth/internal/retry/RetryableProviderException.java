package ai.doctruth.internal.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import ai.doctruth.ProviderException;

/**
 * Internal subclass of {@link ProviderException} carrying a parsed {@code Retry-After}
 * hint (RFC 7231 §7.1.3). Thrown by {@code JsonHttpClient} for retryable HTTP status
 * codes and consumed by {@link RetryGate}; never escapes the internal layer — callers see
 * it as the public {@link ProviderException} via Java subtype polymorphism.
 *
 * <p>This subclass exists instead of widening the public {@link ProviderException}
 * signature (which would be a breaking change per CONTRIBUTING.md "Public API contracts"). A
 * ThreadLocal alternative was rejected because it interacts poorly with virtual threads
 * (per CONTRIBUTING.md "Concurrency").
 *
 * <p>NOT public API.
 *
 * @hidden
 */
public final class RetryableProviderException extends ProviderException {

    private static final long serialVersionUID = 1L;

    // Optional<Duration> isn't Serializable; we store millis + presence flag and repackage
    // in the accessor (matches the OptionalInt pattern used in ProviderException itself).
    private final long minRetryDelayMillisValue;
    private final boolean minRetryDelayPresent;

    public RetryableProviderException(
            String errorCode,
            String message,
            String providerName,
            OptionalInt httpStatus,
            Throwable cause,
            Optional<Duration> minRetryDelay) {
        super(errorCode, message, providerName, httpStatus, true, cause);
        Objects.requireNonNull(minRetryDelay, "minRetryDelay");
        this.minRetryDelayPresent = minRetryDelay.isPresent();
        this.minRetryDelayMillisValue =
                this.minRetryDelayPresent ? minRetryDelay.get().toMillis() : 0L;
    }

    /**
     * The vendor-supplied "wait at least this long before retrying" hint, parsed from the
     * {@code Retry-After} response header. Empty if the header was absent or unparseable.
     */
    public Optional<Duration> minRetryDelay() {
        return minRetryDelayPresent ? Optional.of(Duration.ofMillis(minRetryDelayMillisValue)) : Optional.empty();
    }
}
