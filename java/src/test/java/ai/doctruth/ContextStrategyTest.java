package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the sealed interface {@link ContextStrategy}.
 *
 * <p>{@code ContextStrategy} is sealed and permits exactly three implementations:
 *
 * <ul>
 *   <li>{@link PriorityTruncate}
 *   <li>{@link SlidingWindow}
 *   <li>{@link Hierarchical}
 * </ul>
 *
 * <p>This phase intentionally keeps the interface method-less; behaviour (e.g. an
 * {@code assemble(ParsedDocument)} method) belongs on concrete strategies. The sealed
 * contract here exists so that consumers can write exhaustive pattern-matching {@code switch}
 * over the strategy family without a {@code default} branch — adding a fourth strategy is
 * therefore a compile-time forcing function across the codebase.
 *
 * <p>Compile-time safety: assigning a non-permitted implementation to a variable typed as
 * {@code ContextStrategy} is a compile error. That property is verified by the Java compiler
 * itself and cannot be exercised at runtime.
 */
class ContextStrategyTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("ContextStrategy is a sealed interface")
        void isSealed() {
            assertThat(ContextStrategy.class.isSealed()).isTrue();
            assertThat(ContextStrategy.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("ContextStrategy permits exactly three subtypes: PriorityTruncate, SlidingWindow, Hierarchical")
        void permitsExactlyThreeSubtypes() {
            var permitted = ContextStrategy.class.getPermittedSubclasses();

            assertThat(permitted).hasSize(3);
            assertThat(permitted)
                    .containsExactlyInAnyOrder(PriorityTruncate.class, SlidingWindow.class, Hierarchical.class);
        }

        @Test
        @DisplayName(
                "a switch over instanceof patterns is exhaustive across the three permitted subtypes without a default branch")
        void exhaustiveSwitchWithoutDefault() {
            List<ContextStrategy> strategies = List.of(
                    new PriorityTruncate(List.of("Qualifications"), 25_000, OverBudgetPolicy.STRICT),
                    new SlidingWindow(4_000, 500),
                    new Hierarchical(3));

            for (var strategy : strategies) {
                // Pattern-matching switch with no default — relies on the sealed contract.
                String label =
                        switch (strategy) {
                            case PriorityTruncate ignored -> "priority";
                            case SlidingWindow ignored -> "sliding";
                            case Hierarchical ignored -> "hierarchical";
                        };
                assertThat(label).isIn("priority", "sliding", "hierarchical");
            }
        }
    }
}
