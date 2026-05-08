package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link BlockKind}.
 *
 * <p>The enum constants are part of the public API surface and frozen at v0.1.0 — adding
 * a new kind is a major version bump per AGENTS.md "Public API contracts". These tests
 * pin the wire-shape against accidental reorderings and unknown-name lookups.
 */
class BlockKindTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("declares the four expected kinds in stable order")
        void declarationOrder() {
            assertThat(BlockKind.values())
                    .containsExactly(BlockKind.HEADING, BlockKind.BODY, BlockKind.LIST, BlockKind.OTHER);
        }

        @Test
        @DisplayName("ordinal positions are stable — HEADING=0, BODY=1, LIST=2, OTHER=3")
        void ordinalsArePinned() {
            assertThat(BlockKind.HEADING.ordinal()).isZero();
            assertThat(BlockKind.BODY.ordinal()).isEqualTo(1);
            assertThat(BlockKind.LIST.ordinal()).isEqualTo(2);
            assertThat(BlockKind.OTHER.ordinal()).isEqualTo(3);
        }

        @Test
        @DisplayName("valueOf round-trips every name")
        void valueOfRoundTrip() {
            for (var kind : BlockKind.values()) {
                assertThat(BlockKind.valueOf(kind.name())).isSameAs(kind);
            }
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("valueOf rejects unknown names with IllegalArgumentException")
        void valueOfUnknown() {
            assertThatThrownBy(() -> BlockKind.valueOf("PARAGRAPH")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
