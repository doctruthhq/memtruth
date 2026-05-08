package ai.doctruth;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-call knobs passed to an {@link LlmProvider} on every request.
 *
 * <p>Invariants (compact constructor):
 *
 * <ul>
 *   <li>{@code maxRetries >= 0}.
 *   <li>{@code timeout} non-null and strictly positive ({@code Duration.ZERO} and any
 *       negative duration reject — a non-positive timeout is meaningless).
 * </ul>
 *
 * @param maxRetries number of retry attempts on retryable failures (0 = first call only).
 * @param timeout    per-call wall-clock timeout; passed verbatim to the JDK HTTP layer.
 * @since 0.1.0
 */
public record ProviderOptions(int maxRetries, Duration timeout) {

    public ProviderOptions {
        Objects.requireNonNull(timeout, "timeout");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
    }
}
