package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link PriorityTruncate}.
 *
 * <p>Invariants this record must enforce (validated in the compact constructor):
 *
 * <ul>
 *   <li>{@code prioritySectionPatterns} is non-null. An empty list IS allowed — it represents
 *       "no priority sections; truncate uniformly to {@code maxChars}". Callers that want to
 *       require at least one priority should validate at the call site.
 *   <li>Each pattern in the list is non-null (rejects with NPE) and non-blank (rejects with
 *       IAE). A {@code null} entry is a programming error; a blank entry would silently match
 *       everything via most regex engines.
 *   <li>{@code maxChars >= 1}. Zero / negative budgets are rejected with IAE.
 *   <li>{@code onOverBudget} is non-null (NPE).
 * </ul>
 *
 * <p>Defensive-copy contract: the record stores an unmodifiable copy of the patterns list.
 * Mutating the input after construction does not mutate the stored list.
 */
class PriorityTruncateTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("accepts a typical priority-section configuration (4 priorities, 25k chars, STRICT)")
        void typicalPrioritySectionConfig() {
            var strategy = new PriorityTruncate(
                    List.of("Qualifications", "Scoring Criteria", "Disqualification", "Contract Terms"),
                    25_000,
                    OverBudgetPolicy.STRICT);

            assertThat(strategy.prioritySectionPatterns()).hasSize(4);
            assertThat(strategy.maxChars()).isEqualTo(25_000);
            assertThat(strategy.onOverBudget()).isEqualTo(OverBudgetPolicy.STRICT);
        }

        @Test
        @DisplayName("accepts an empty prioritySectionPatterns list (no priority; uniform truncation)")
        void emptyPatternsAllowed() {
            var strategy = new PriorityTruncate(List.of(), 1_000, OverBudgetPolicy.WARN_AND_INCLUDE);

            assertThat(strategy.prioritySectionPatterns()).isEmpty();
            assertThat(strategy.maxChars()).isEqualTo(1_000);
            assertThat(strategy.onOverBudget()).isEqualTo(OverBudgetPolicy.WARN_AND_INCLUDE);
        }

        @Test
        @DisplayName("accepts maxChars = 1 (minimum non-zero budget)")
        void maxCharsOne() {
            var strategy = new PriorityTruncate(List.of(), 1, OverBudgetPolicy.STRICT);

            assertThat(strategy.maxChars()).isEqualTo(1);
        }

        @Test
        @DisplayName("two PriorityTruncates with equal fields are equal (record semantics)")
        void recordEquality() {
            var a = new PriorityTruncate(List.of("A"), 100, OverBudgetPolicy.STRICT);
            var b = new PriorityTruncate(List.of("A"), 100, OverBudgetPolicy.STRICT);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects null prioritySectionPatterns with NullPointerException")
        void nullPatternsList() {
            assertThatThrownBy(() -> new PriorityTruncate(null, 100, OverBudgetPolicy.STRICT))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("prioritySectionPatterns");
        }

        @Test
        @DisplayName("rejects a list containing a null pattern entry with NullPointerException")
        void nullPatternEntry() {
            // Use Arrays.asList to allow nulls (List.of would NPE on construction itself).
            var withNull = Arrays.asList("Qualifications", null);

            assertThatThrownBy(() -> new PriorityTruncate(withNull, 100, OverBudgetPolicy.STRICT))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("prioritySectionPatterns");
        }

        @Test
        @DisplayName("rejects a list containing a blank pattern entry with IllegalArgumentException")
        void blankPatternEntry() {
            assertThatThrownBy(
                            () -> new PriorityTruncate(List.of("Qualifications", "  "), 100, OverBudgetPolicy.STRICT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("prioritySectionPatterns");
        }

        @Test
        @DisplayName("rejects a list containing an empty pattern entry with IllegalArgumentException")
        void emptyPatternEntry() {
            assertThatThrownBy(() -> new PriorityTruncate(List.of("Qualifications", ""), 100, OverBudgetPolicy.STRICT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("prioritySectionPatterns");
        }

        @Test
        @DisplayName("rejects maxChars = 0 with IllegalArgumentException")
        void maxCharsZero() {
            assertThatThrownBy(() -> new PriorityTruncate(List.of(), 0, OverBudgetPolicy.STRICT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxChars");
        }

        @Test
        @DisplayName("rejects maxChars = -1 with IllegalArgumentException")
        void maxCharsNegative() {
            assertThatThrownBy(() -> new PriorityTruncate(List.of(), -1, OverBudgetPolicy.STRICT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxChars");
        }

        @Test
        @DisplayName("rejects null onOverBudget with NullPointerException")
        void nullOnOverBudget() {
            assertThatThrownBy(() -> new PriorityTruncate(List.of(), 100, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("onOverBudget");
        }
    }

    @Nested
    @DisplayName("defensive copy")
    class DefensiveCopy {

        @Test
        @DisplayName("mutating the input patterns list after construction does not affect the stored list")
        void mutationOfInputDoesNotLeak() {
            var patterns = new ArrayList<String>();
            patterns.add("Qualifications");

            var strategy = new PriorityTruncate(patterns, 100, OverBudgetPolicy.STRICT);
            patterns.add("Scoring");

            assertThat(strategy.prioritySectionPatterns()).hasSize(1);
            assertThat(strategy.prioritySectionPatterns()).containsExactly("Qualifications");
        }

        @Test
        @DisplayName("calling prioritySectionPatterns().add(...) throws UnsupportedOperationException")
        void patternsListIsUnmodifiable() {
            var strategy =
                    new PriorityTruncate(new ArrayList<>(List.of("Qualifications")), 100, OverBudgetPolicy.STRICT);

            assertThatThrownBy(() -> strategy.prioritySectionPatterns().add("Scoring"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
