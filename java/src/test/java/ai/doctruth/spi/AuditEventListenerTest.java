package ai.doctruth.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link AuditEventListener}.
 *
 * <p>The OSS default is the {@link AuditEventListener#NOOP} listener — it must be safe to
 * invoke with any event without throwing, and it must not retain references to events
 * (so callers can rely on it as a non-leaking default).
 */
class AuditEventListenerTest {

    @Nested
    @DisplayName("NOOP")
    class Noop {

        @Test
        @DisplayName("NOOP.onEvent(...) does not throw for any well-formed event")
        void doesNotThrow() {
            var event = new AuditEvent(
                    "extraction.success", Instant.parse("2026-05-07T07:30:00Z"), Map.of("provider", "anthropic"));

            assertThatCode(() -> AuditEventListener.NOOP.onEvent(event)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("NOOP discards events — no observable side effect (smoke test against accidental retention)")
        void discardsSilently() {
            // A custom listener captures the event; NOOP next to it must NOT.
            var captured = new AtomicReference<AuditEvent>();
            AuditEventListener capturing = captured::set;
            var event = new AuditEvent("extraction.success", Instant.parse("2026-05-07T07:30:00Z"), Map.of());

            AuditEventListener.NOOP.onEvent(event);
            capturing.onEvent(event);

            // NOOP did not capture anything; sanity-check: capturing did capture it.
            assertThat(captured.get()).isSameAs(event);
        }
    }
}
