package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link OverBudgetPolicy}.
 *
 * <p>{@code OverBudgetPolicy} models the two ways a context-assembly strategy can react when
 * the assembled context would exceed its configured character budget:
 *
 * <ul>
 *   <li>{@code STRICT} — fail loudly (throw); never silently truncate or pad-include.
 *   <li>{@code WARN_AND_INCLUDE} — emit an SLF4J {@code warn} event and include anyway, leaving
 *       the budget overrun visible to the caller via observability.
 * </ul>
 *
 * <p>Why an enum, not a sealed interface: per CONTRIBUTING.md "Engineering principles" §4 ("Build,
 * don't synthesize"), a fixed, finite set of value-only constants is the canonical Java idiom
 * for {@code enum}. A sealed interface would be appropriate if each policy carried its own
 * configuration (e.g. callbacks, thresholds) — they do not. Promoting to a sealed interface
 * later is a backward-incompatible change, so this decision is recorded here as a contract:
 * if a policy ever needs per-instance state, that promotion is a major version bump.
 */
class OverBudgetPolicyTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("values() returns exactly {STRICT, WARN_AND_INCLUDE} in declaration order")
        void valuesInDeclarationOrder() {
            var values = OverBudgetPolicy.values();

            assertThat(values).containsExactly(OverBudgetPolicy.STRICT, OverBudgetPolicy.WARN_AND_INCLUDE);
        }

        @Test
        @DisplayName("valueOf(\"STRICT\") round-trips to OverBudgetPolicy.STRICT")
        void valueOfStrictRoundTrip() {
            assertThat(OverBudgetPolicy.valueOf("STRICT")).isEqualTo(OverBudgetPolicy.STRICT);
        }

        @Test
        @DisplayName("valueOf(\"WARN_AND_INCLUDE\") round-trips to OverBudgetPolicy.WARN_AND_INCLUDE")
        void valueOfWarnAndIncludeRoundTrip() {
            assertThat(OverBudgetPolicy.valueOf("WARN_AND_INCLUDE")).isEqualTo(OverBudgetPolicy.WARN_AND_INCLUDE);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("valueOf(\"missing\") throws IllegalArgumentException (unknown enum constant)")
        void valueOfUnknownThrows() {
            assertThatThrownBy(() -> OverBudgetPolicy.valueOf("missing")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
