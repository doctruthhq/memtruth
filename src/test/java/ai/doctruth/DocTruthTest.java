package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link DocTruth}, the entry point of the fluent extraction API.
 *
 * <p>The class itself is a tiny façade: it owns a non-null {@link LlmProvider} reference and
 * mints a fresh {@link ExtractionBuilder} per {@code extract(...)} call. All the
 * orchestration semantics (retry, provenance assembly, JSON parsing) live on the builder
 * and are covered in {@link ExtractionBuilderTest}; this file pins the entry-point
 * invariants only.
 *
 * <p>Note on the fake provider: per AGENTS.md "no god files / classes" we keep one anonymous
 * subclass per test method rather than introducing a shared helper class. The subclass
 * pattern (and the rationale for {@code AnthropicProvider} being non-final) is documented in
 * {@link AnthropicProviderTest}.
 */
class DocTruthTest {

    /** Fixture record per the test plan — kept inline; do not promote to a top-level type. */
    record Person(String name, int age) {}

    private static ParsedDocument sampleDoc() {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var section = new TextSection("Alex Chen, 30 years old", loc);
        var meta = new DocumentMetadata("test.pdf", 1, Optional.empty());
        return new ParsedDocument("doc-1", List.of(section), meta);
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("DocTruth.from(provider) returns a non-null DocTruth instance")
        void fromReturnsNonNull() {
            var provider = new AnthropicProvider("test-key");

            var docTruth = DocTruth.from(provider);

            assertThat(docTruth).isNotNull();
        }

        @Test
        @DisplayName("extract(prompt, type) returns a non-null ExtractionBuilder<T>")
        void extractReturnsNonNullBuilder() {
            var provider = new AnthropicProvider("test-key");

            var builder = DocTruth.from(provider).extract("Extract the person.", Person.class);

            assertThat(builder).isNotNull();
        }

        @Test
        @DisplayName("extract is generic in T — a builder built for Person.class produces an "
                + "ExtractionResult<Person> when run() against a Person-shaped payload")
        void extractIsGenericInT() throws ExtractionException {
            var canned = new ProviderResponse(
                    "{\"name\":\"Alex\",\"age\":30}", new ProviderUsage(120, 18, "claude-sonnet-4-7-test"));
            var calls = new AtomicInteger();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    calls.incrementAndGet();
                    return canned;
                }
            };

            var result = DocTruth.from(fake)
                    .extract("Extract the person.", Person.class)
                    .run(sampleDoc());

            assertThat(result).isNotNull();
            assertThat(result.value()).isInstanceOf(Person.class);
            assertThat(result.value()).isEqualTo(new Person("Alex", 30));
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("from(null) throws NullPointerException mentioning \"provider\"")
        void fromNullProvider() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DocTruth.from(null))
                    .withMessageContaining("provider");
        }

        @Test
        @DisplayName("extract(null, Person.class) throws NullPointerException mentioning \"prompt\"")
        void extractNullPrompt() {
            var docTruth = DocTruth.from(new AnthropicProvider("test-key"));

            assertThatNullPointerException()
                    .isThrownBy(() -> docTruth.extract(null, Person.class))
                    .withMessageContaining("prompt");
        }

        @Test
        @DisplayName("extract(\"  \", Person.class) throws IllegalArgumentException mentioning \"prompt\"")
        void extractBlankPrompt() {
            var docTruth = DocTruth.from(new AnthropicProvider("test-key"));

            assertThatThrownBy(() -> docTruth.extract("  ", Person.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("prompt");
        }

        @Test
        @DisplayName("extract(\"ok\", null) throws NullPointerException mentioning \"type\"")
        void extractNullType() {
            var docTruth = DocTruth.from(new AnthropicProvider("test-key"));

            assertThatNullPointerException()
                    .isThrownBy(() -> docTruth.extract("ok", null))
                    .withMessageContaining("type");
        }
    }
}
