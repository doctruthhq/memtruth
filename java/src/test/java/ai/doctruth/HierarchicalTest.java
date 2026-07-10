package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link Hierarchical}.
 *
 * <p>{@code Hierarchical} is a Phase-3 placeholder. The current shape captures only the single
 * invariant the family already needs ({@code maxDepth >= 1}); richer configuration — per-level
 * summarisers, stop conditions, fan-out limits — may extend this record's components in a
 * future major version. Tests intentionally cover only the minimum invariants so the placeholder
 * does not over-fit a future design.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code maxDepth >= 1}. Zero / negative depth is nonsensical and rejected with IAE.
 * </ul>
 */
class HierarchicalTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts maxDepth = 1 (minimum valid depth — flat hierarchy)")
        void maxDepthOne() {
            var strategy = new Hierarchical(1);

            assertThat(strategy.maxDepth()).isEqualTo(1);
        }

        @Test
        @DisplayName("accepts a typical maxDepth = 3 (document → section → paragraph)")
        void maxDepthThree() {
            var strategy = new Hierarchical(3);

            assertThat(strategy.maxDepth()).isEqualTo(3);
        }

        @Test
        @DisplayName("two Hierarchicals with equal maxDepth are equal (record semantics)")
        void recordEquality() {
            var a = new Hierarchical(3);
            var b = new Hierarchical(3);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects maxDepth = 0 with IllegalArgumentException")
        void maxDepthZero() {
            assertThatThrownBy(() -> new Hierarchical(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxDepth");
        }

        @Test
        @DisplayName("rejects maxDepth = -1 with IllegalArgumentException")
        void maxDepthNegative() {
            assertThatThrownBy(() -> new Hierarchical(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxDepth");
        }
    }
}
