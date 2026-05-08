package ai.doctruth.internal.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import ai.doctruth.ProviderException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link RetryGate}.
 *
 * <p>The gate wraps Failsafe so callers see only {@link ProviderException}s. Tests use
 * tiny delays ({@link Duration#ofMillis(long) Duration.ofMillis(1)}) so the suite stays
 * under 100 ms wall-clock, and an in-memory {@link AtomicInteger} counter to verify how
 * many times the supplier was actually invoked.
 */
class RetryGateTest {

    private static final String PROVIDER = "anthropic";
    private static final Duration TINY = Duration.ofMillis(1);
    private static final Duration TINY_MAX = Duration.ofMillis(2);

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("supplier succeeds first try → result returned, called exactly once")
        void firstTrySuccess() throws ProviderException {
            var gate = new RetryGate(3, TINY, TINY_MAX);
            var calls = new AtomicInteger();

            String result = gate.run(
                    () -> {
                        calls.incrementAndGet();
                        return "ok";
                    },
                    PROVIDER);

            assertThat(result).isEqualTo("ok");
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("retry")
    class Retry {

        @Test
        @DisplayName("retryable failure on attempts 1 & 2, success on 3 → returns result, 3 calls")
        void retryableUntilSuccess() throws ProviderException {
            var gate = new RetryGate(2, TINY, TINY_MAX);
            var calls = new AtomicInteger();

            String result = gate.run(
                    () -> {
                        int n = calls.incrementAndGet();
                        if (n < 3) {
                            throw retryable("attempt " + n);
                        }
                        return "ok";
                    },
                    PROVIDER);

            assertThat(result).isEqualTo("ok");
            assertThat(calls.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("retryable failure on every attempt → throws after maxRetries+1 calls")
        void retryableExhausted() {
            var gate = new RetryGate(2, TINY, TINY_MAX);
            var calls = new AtomicInteger();

            assertThatThrownBy(() -> gate.run(
                            () -> {
                                calls.incrementAndGet();
                                throw retryable("never settles");
                            },
                            PROVIDER))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.providerName()).isEqualTo(PROVIDER);
                        assertThat(ex.retryable()).isTrue();
                    });
            assertThat(calls.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("maxRetries=0 + retryable failure → throws after first call (no retry)")
        void zeroRetries() {
            var gate = new RetryGate(0, TINY, TINY_MAX);
            var calls = new AtomicInteger();

            assertThatThrownBy(() -> gate.run(
                            () -> {
                                calls.incrementAndGet();
                                throw retryable("once");
                            },
                            PROVIDER))
                    .isInstanceOf(ProviderException.class);
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("abort")
    class Abort {

        @Test
        @DisplayName("non-retryable failure → throws after first call (no retry)")
        void nonRetryableFailFast() {
            var gate = new RetryGate(5, TINY, TINY_MAX);
            var calls = new AtomicInteger();

            assertThatThrownBy(() -> gate.run(
                            () -> {
                                calls.incrementAndGet();
                                throw nonRetryable("permanent");
                            },
                            PROVIDER))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.retryable()).isFalse();
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_HTTP_401");
                    });
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("unexpected throwables")
    class Unexpected {

        @Test
        @DisplayName("non-ProviderException → wrapped as RETRY_UNEXPECTED, non-retryable")
        void unexpectedThrowableWrapped() {
            var gate = new RetryGate(2, TINY, TINY_MAX);
            var calls = new AtomicInteger();

            assertThatThrownBy(() -> gate.run(
                            () -> {
                                calls.incrementAndGet();
                                throw new IllegalStateException("boom");
                            },
                            PROVIDER))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("RETRY_UNEXPECTED");
                        assertThat(ex.retryable()).isFalse();
                        assertThat(ex.providerName()).isEqualTo(PROVIDER);
                        assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class);
                    });
            // Unexpected throwables are not retried — they fail fast.
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("constructor")
    class Construction {

        @Test
        @DisplayName("negative maxRetries rejected")
        void negativeMaxRetries() {
            assertThatThrownBy(() -> new RetryGate(-1, TINY, TINY_MAX)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null initialDelay rejected")
        void nullInitialDelay() {
            assertThatThrownBy(() -> new RetryGate(2, null, TINY_MAX)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null maxDelay rejected")
        void nullMaxDelay() {
            assertThatThrownBy(() -> new RetryGate(2, TINY, null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("honours Retry-After hint")
    class HonoursRetryAfter {

        @Test
        @DisplayName("hint of 50ms → actual delay between attempts is at least ~50ms")
        void usesHintWhenPresent() throws ProviderException {
            // Use generous initial/max delays so default exponential would dominate if we
            // failed to honour the hint. With initialDelay=1ms and a 50ms hint, the only
            // way the gap reaches 50ms is via the Retry-After-driven delay function.
            var gate = new RetryGate(2, Duration.ofMillis(1), Duration.ofMillis(500));
            var calls = new AtomicInteger();
            var firstCallNanos = new AtomicLong();
            var secondCallNanos = new AtomicLong();

            String result = gate.run(
                    () -> {
                        int n = calls.incrementAndGet();
                        if (n == 1) {
                            firstCallNanos.set(System.nanoTime());
                            throw retryableWithHint("first", Optional.of(Duration.ofMillis(50)));
                        }
                        secondCallNanos.set(System.nanoTime());
                        return "ok";
                    },
                    PROVIDER);

            assertThat(result).isEqualTo("ok");
            long elapsedMs = (secondCallNanos.get() - firstCallNanos.get()) / 1_000_000L;
            // Allow scheduler slop (CI may be slow) but must be ≥ 40ms — well above
            // the 1ms default exponential.
            assertThat(elapsedMs).isGreaterThanOrEqualTo(40);
        }

        @Test
        @DisplayName("hint absent → falls back to exponential (delay close to initial)")
        void fallbackWhenHintEmpty() throws ProviderException {
            var gate = new RetryGate(2, Duration.ofMillis(5), Duration.ofMillis(50));
            var calls = new AtomicInteger();
            var firstCallNanos = new AtomicLong();
            var secondCallNanos = new AtomicLong();

            String result = gate.run(
                    () -> {
                        int n = calls.incrementAndGet();
                        if (n == 1) {
                            firstCallNanos.set(System.nanoTime());
                            throw retryableWithHint("first", Optional.empty());
                        }
                        secondCallNanos.set(System.nanoTime());
                        return "ok";
                    },
                    PROVIDER);

            assertThat(result).isEqualTo("ok");
            long elapsedMs = (secondCallNanos.get() - firstCallNanos.get()) / 1_000_000L;
            // Without a hint, the delay should be ~initialDelay (5ms) plus jitter, well
            // below 200ms. We assert an upper bound to prove we did NOT wait a long time.
            assertThat(elapsedMs).isLessThan(200);
        }

        @Test
        @DisplayName("hint exceeds maxDelay → clamped to maxDelay (do NOT wait the full hint)")
        void hintCappedByMaxDelay() throws ProviderException {
            // Hint says 500ms but maxDelay is 100ms — actual sleep should be ~100ms.
            var gate = new RetryGate(2, Duration.ofMillis(10), Duration.ofMillis(100));
            var calls = new AtomicInteger();
            var firstCallNanos = new AtomicLong();
            var secondCallNanos = new AtomicLong();

            String result = gate.run(
                    () -> {
                        int n = calls.incrementAndGet();
                        if (n == 1) {
                            firstCallNanos.set(System.nanoTime());
                            throw retryableWithHint("first", Optional.of(Duration.ofMillis(500)));
                        }
                        secondCallNanos.set(System.nanoTime());
                        return "ok";
                    },
                    PROVIDER);

            assertThat(result).isEqualTo("ok");
            long elapsedMs = (secondCallNanos.get() - firstCallNanos.get()) / 1_000_000L;
            // Must be well under the literal 500ms hint — we proved we capped.
            assertThat(elapsedMs).isLessThan(400);
        }
    }

    private static ProviderException retryable(String msg) {
        return new ProviderException("PROVIDER_HTTP_500", msg, PROVIDER, OptionalInt.of(500), true);
    }

    private static ProviderException nonRetryable(String msg) {
        return new ProviderException("PROVIDER_HTTP_401", msg, PROVIDER, OptionalInt.of(401), false);
    }

    private static RetryableProviderException retryableWithHint(String msg, Optional<Duration> hint) {
        return new RetryableProviderException("PROVIDER_HTTP_429", msg, PROVIDER, OptionalInt.of(429), null, hint);
    }
}
