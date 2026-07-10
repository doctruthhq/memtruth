package ai.doctruth.internal.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link RetryAfterParser}.
 *
 * <p>Covers RFC 7231 §7.1.3 — both delta-seconds and HTTP-date forms — plus the malformed
 * inputs we expect from misbehaving upstreams. The parser is pure (no clock, no I/O) so
 * tests pass {@code now} explicitly to keep HTTP-date arithmetic deterministic.
 */
class RetryAfterParserTest {

    @Nested
    @DisplayName("delta-seconds form")
    class DeltaSeconds {

        @Test
        @DisplayName("\"30\" → 30 seconds")
        void thirtySeconds() {
            assertThat(RetryAfterParser.parse("30", Instant.EPOCH)).contains(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("\"0\" → Duration.ZERO")
        void zeroSeconds() {
            assertThat(RetryAfterParser.parse("0", Instant.EPOCH)).contains(Duration.ZERO);
        }

        @Test
        @DisplayName("leading/trailing whitespace tolerated")
        void whitespaceTolerated() {
            assertThat(RetryAfterParser.parse("  42  ", Instant.EPOCH)).contains(Duration.ofSeconds(42));
        }
    }

    @Nested
    @DisplayName("HTTP-date form")
    class HttpDate {

        @Test
        @DisplayName("date 30 seconds in the future → ~30 seconds")
        void thirtySecondsInFuture() {
            Instant now = Instant.parse("2026-10-21T07:27:30Z");
            String header = "Wed, 21 Oct 2026 07:28:00 GMT";

            var parsed = RetryAfterParser.parse(header, now);

            assertThat(parsed).isPresent();
            // Allow up to 1 second slop because HTTP-date resolution is whole seconds.
            assertThat(parsed.get()).isBetween(Duration.ofSeconds(29), Duration.ofSeconds(31));
        }

        @Test
        @DisplayName("date in the past → empty (negative interval rejected)")
        void datePast() {
            Instant now = Instant.parse("2026-10-21T08:00:00Z");
            String header = "Wed, 21 Oct 2026 07:28:00 GMT";

            assertThat(RetryAfterParser.parse(header, now)).isEmpty();
        }

        @Test
        @DisplayName("formatted via DateTimeFormatter.RFC_1123_DATE_TIME round-trips")
        void roundTrip() {
            Instant now = Instant.parse("2026-05-07T12:00:00Z");
            Instant target = now.plus(Duration.ofMinutes(5));
            String header =
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(target, ZoneOffset.UTC));

            var parsed = RetryAfterParser.parse(header, now);

            assertThat(parsed).isPresent();
            assertThat(parsed.get())
                    .isBetween(
                            Duration.ofMinutes(5).minusSeconds(1),
                            Duration.ofMinutes(5).plusSeconds(1));
        }
    }

    @Nested
    @DisplayName("absent / malformed")
    class Edge {

        @Test
        @DisplayName("null → empty")
        void nullValue() {
            assertThat(RetryAfterParser.parse(null, Instant.EPOCH)).isEmpty();
        }

        @Test
        @DisplayName("empty string → empty")
        void emptyString() {
            assertThat(RetryAfterParser.parse("", Instant.EPOCH)).isEmpty();
        }

        @Test
        @DisplayName("blank string → empty")
        void blankString() {
            assertThat(RetryAfterParser.parse("   ", Instant.EPOCH)).isEmpty();
        }

        @Test
        @DisplayName("\"abc\" → empty (not parseable)")
        void garbage() {
            assertThat(RetryAfterParser.parse("abc", Instant.EPOCH)).isEmpty();
        }

        @Test
        @DisplayName("\"-1\" → empty (negative seconds rejected)")
        void negativeSeconds() {
            assertThat(RetryAfterParser.parse("-1", Instant.EPOCH)).isEmpty();
        }

        @Test
        @DisplayName("garbage HTTP-date → empty")
        void garbageDate() {
            assertThat(RetryAfterParser.parse("Wed, 99 Foo 9999 99:99:99 ZZZ", Instant.EPOCH))
                    .isEmpty();
        }
    }
}
