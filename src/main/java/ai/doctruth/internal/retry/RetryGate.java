package ai.doctruth.internal.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import ai.doctruth.ProviderException;

import dev.failsafe.ExecutionContext;
import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeException;
import dev.failsafe.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Failsafe-backed retry gate. Wraps a {@link Callable} and retries it on retryable
 * {@link ProviderException}s with exponential backoff and jitter. Failsafe types
 * ({@code dev.failsafe.*}) are confined to this class — callers see only
 * {@link ProviderException}.
 *
 * <p>NOT public API — see {@code package-info.java}.
 *
 * <p>Retry semantics (per ADR 0004):
 *
 * <ul>
 *   <li>Retryable iff the thrown exception is a {@link ProviderException} with
 *       {@link ProviderException#retryable() retryable=true}.
 *   <li>Non-retryable {@link ProviderException} aborts immediately (fail fast).
 *   <li>Any other {@link Throwable} is wrapped as
 *       {@code ProviderException("RETRY_UNEXPECTED", ..., retryable=false)} and not retried.
 *   <li>Backoff: exponential from {@code initialDelay} to {@code maxDelay} with 10% jitter.
 * </ul>
 *
 * @hidden
 */
public final class RetryGate {

    private static final Logger log = LoggerFactory.getLogger(RetryGate.class);

    private static final double JITTER_FACTOR = 0.1;

    private final int maxRetries;
    private final Duration initialDelay;
    private final Duration maxDelay;

    public RetryGate(int maxRetries, Duration initialDelay, Duration maxDelay) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
        }
        this.maxRetries = maxRetries;
        this.initialDelay = Objects.requireNonNull(initialDelay, "initialDelay");
        this.maxDelay = Objects.requireNonNull(maxDelay, "maxDelay");
    }

    /**
     * Run {@code supplier} under the configured retry policy.
     *
     * @throws ProviderException if the supplier exhausts retries, fails non-retryably, or
     *     throws an unexpected {@link Throwable} (which is wrapped as {@code RETRY_UNEXPECTED}).
     */
    public <T> T run(Callable<T> supplier, String providerName) throws ProviderException {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(providerName, "providerName");

        RetryPolicy<T> policy = buildPolicy(providerName);
        try {
            return Failsafe.with(policy).get(supplier::call);
        } catch (FailsafeException e) {
            throw unwrap(e, providerName);
        } catch (RuntimeException e) {
            // Defensive: Failsafe lets RuntimeExceptions through unwrapped on some paths.
            throw wrapUnexpected(e, providerName);
        }
    }

    private <T> RetryPolicy<T> buildPolicy(String providerName) {
        // withDelayFn replaces withBackoff entirely (Failsafe 3.x), so we compute
        // exponential-with-jitter ourselves and override only when the upstream sent a
        // Retry-After hint via RetryableProviderException.
        return RetryPolicy.<T>builder()
                .withMaxRetries(maxRetries)
                .withDelayFn((ExecutionContext<T> ctx) -> nextDelay(ctx))
                .handleIf(t -> t instanceof ProviderException pe && pe.retryable())
                .abortIf(t -> t instanceof ProviderException pe && !pe.retryable())
                .onRetry(event -> log.warn(
                        "retrying provider={} attempt={}/{} cause={}",
                        providerName,
                        event.getAttemptCount(),
                        maxRetries + 1,
                        describe(event.getLastException())))
                .build();
    }

    private <T> Duration nextDelay(ExecutionContext<T> ctx) {
        Throwable last = ctx.getLastException();
        Optional<Duration> hint = retryAfterHint(last);
        if (hint.isPresent()) {
            // Vendor signal overrides our backoff for this single attempt; clamp at
            // maxDelay so a hostile / mistaken upstream can't park us for hours.
            long capped = Math.min(hint.get().toMillis(), maxDelay.toMillis());
            return Duration.ofMillis(Math.max(0L, capped));
        }
        return exponentialBackoff(ctx.getAttemptCount());
    }

    private static Optional<Duration> retryAfterHint(Throwable t) {
        if (t instanceof RetryableProviderException rpe) {
            return rpe.minRetryDelay();
        }
        return Optional.empty();
    }

    private Duration exponentialBackoff(int attemptCount) {
        // attemptCount is 1-based after the first failure; shift produces 1, 2, 4, ...
        int shift = Math.max(0, Math.min(30, attemptCount - 1));
        long base = initialDelay.toMillis() * (1L << shift);
        long capped = Math.min(maxDelay.toMillis(), Math.max(0L, base));
        long jitterRange = (long) (capped * JITTER_FACTOR);
        long jitter = jitterRange == 0 ? 0L : ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1);
        long withJitter = Math.max(0L, capped + jitter);
        return Duration.ofMillis(withJitter);
    }

    private static ProviderException unwrap(FailsafeException e, String providerName) {
        Throwable cause = e.getCause();
        if (cause instanceof ProviderException pe) {
            return pe;
        }
        if (cause == null) {
            return new ProviderException(
                    "RETRY_UNEXPECTED",
                    "Failsafe failed without a cause for provider=" + providerName,
                    providerName,
                    OptionalInt.empty(),
                    false,
                    e);
        }
        return wrapUnexpected(cause, providerName);
    }

    private static ProviderException wrapUnexpected(Throwable cause, String providerName) {
        return new ProviderException(
                "RETRY_UNEXPECTED",
                "unexpected throwable in retry-gated supplier for provider="
                        + providerName
                        + ": "
                        + cause.getClass().getSimpleName()
                        + (cause.getMessage() == null ? "" : (": " + cause.getMessage())),
                providerName,
                OptionalInt.empty(),
                false,
                cause);
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "<none>";
        }
        return t.getClass().getSimpleName() + ":" + t.getMessage();
    }
}
