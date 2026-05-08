package ai.doctruth.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link AuditEvent}.
 *
 * <p>Invariants:
 *
 * <ul>
 *   <li>{@code kind} non-null and non-blank.
 *   <li>{@code at} non-null.
 *   <li>{@code attributes} non-null; defensively copied; accessor returns unmodifiable view.
 * </ul>
 */
class AuditEventTest {

    private static final Instant NOW = Instant.parse("2026-05-07T07:30:00Z");

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a fully-populated event with non-empty attributes")
        void typicalEvent() {
            var event = new AuditEvent("extraction.success", NOW, Map.of("provider", "anthropic"));

            assertThat(event.kind()).isEqualTo("extraction.success");
            assertThat(event.at()).isEqualTo(NOW);
            assertThat(event.attributes()).containsEntry("provider", "anthropic");
        }

        @Test
        @DisplayName("accepts an empty attributes map")
        void emptyAttributesAllowed() {
            var event = new AuditEvent("extraction.success", NOW, Map.of());

            assertThat(event.attributes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null kind with NullPointerException")
        void nullKind() {
            assertThatThrownBy(() -> new AuditEvent(null, NOW, Map.of()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("kind");
        }

        @Test
        @DisplayName("rejects empty kind with IllegalArgumentException")
        void emptyKind() {
            assertThatThrownBy(() -> new AuditEvent("", NOW, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("kind");
        }

        @Test
        @DisplayName("rejects whitespace-only kind with IllegalArgumentException")
        void blankKind() {
            assertThatThrownBy(() -> new AuditEvent("   ", NOW, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("kind");
        }

        @Test
        @DisplayName("rejects null at with NullPointerException")
        void nullAt() {
            assertThatThrownBy(() -> new AuditEvent("extraction.success", null, Map.of()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("at");
        }

        @Test
        @DisplayName("rejects null attributes with NullPointerException")
        void nullAttributes() {
            assertThatThrownBy(() -> new AuditEvent("extraction.success", NOW, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("attributes");
        }
    }

    @Nested
    @DisplayName("defensive copy")
    class DefensiveCopy {

        @Test
        @DisplayName("mutating the input attributes map after construction does not mutate event.attributes()")
        void inputMapIsCopied() {
            Map<String, String> mutable = new HashMap<>();
            mutable.put("provider", "anthropic");

            var event = new AuditEvent("extraction.success", NOW, mutable);

            mutable.put("rogue", "value");

            assertThat(event.attributes()).hasSize(1).containsOnlyKeys("provider");
        }

        @Test
        @DisplayName("event.attributes() is unmodifiable — put() throws UnsupportedOperationException")
        void accessorIsUnmodifiable() {
            var event = new AuditEvent("extraction.success", NOW, Map.of("provider", "anthropic"));

            assertThatThrownBy(() -> event.attributes().put("rogue", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
