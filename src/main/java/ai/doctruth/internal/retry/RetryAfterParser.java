package ai.doctruth.internal.retry;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Parses the HTTP {@code Retry-After} response header (RFC 7231 §7.1.3). The header
 * comes in two forms:
 *
 * <ul>
 *   <li>Delta-seconds: {@code Retry-After: 30}
 *   <li>HTTP-date: {@code Retry-After: Wed, 21 Oct 2026 07:28:00 GMT}
 * </ul>
 *
 * <p>Output is an {@link Optional} {@link Duration} representing "wait at least this long
 * before retrying". Negative deltas, dates already in the past, malformed input, or
 * {@code null}/blank input all yield {@link Optional#empty()} (the caller treats that as
 * "no hint" and uses default backoff).
 *
 * <p>Pure helper — no I/O, no logging, no clock dependency. The {@code now} parameter is
 * passed in by the caller for testability.
 *
 * <p>NOT public API.
 *
 * @hidden
 */
public final class RetryAfterParser {

    private RetryAfterParser() {
        throw new AssertionError("no instances");
    }

    /**
     * @param headerValue the raw {@code Retry-After} header value (may be {@code null})
     * @param now reference instant against which to evaluate HTTP-date forms
     * @return the parsed minimum delay, or empty if absent / unparseable / non-positive
     */
    public static Optional<Duration> parse(String headerValue, Instant now) {
        if (headerValue == null) {
            return Optional.empty();
        }
        String trimmed = headerValue.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        Optional<Duration> seconds = parseDeltaSeconds(trimmed);
        if (seconds.isPresent()) {
            return seconds;
        }
        return parseHttpDate(trimmed, now);
    }

    private static Optional<Duration> parseDeltaSeconds(String trimmed) {
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> parseHttpDate(String trimmed, Instant now) {
        try {
            Instant target = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(trimmed));
            Duration delta = Duration.between(now, target);
            return delta.isNegative() ? Optional.empty() : Optional.of(delta);
        } catch (RuntimeException ignored) {
            // Catch RuntimeException (covers DateTimeParseException + DateTimeException)
            // — a malformed header is a "hint absent" not a programmer error.
            return Optional.empty();
        }
    }
}
