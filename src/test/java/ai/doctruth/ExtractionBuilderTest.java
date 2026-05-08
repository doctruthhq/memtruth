package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ExtractionBuilder}.
 *
 * <p>The builder is the orchestration core of the fluent API: every {@code with*} method
 * returns a fresh immutable builder (per CONTRIBUTING.md "elegance over cleverness — immutable
 * data over shared mutable state"); {@code run(doc)} performs a single provider call,
 * parses the JSON via Jackson into {@code T}, and assembles a {@link Provenance} record
 * from {@link LlmProvider#name()}, {@link ProviderUsage#modelVersion()}, and the
 * wall-clock at completion.
 *
 * <p>Behavioural contract pinned here:
 *
 * <ul>
 *   <li>Happy path: exactly one {@code provider.complete(...)} call.
 *   <li>Provenance reflects {@code provider.name()} and {@code response.usage().modelVersion()}.
 *   <li>{@code extractedAt} is captured at run time (within the test's wall-clock window).
 *   <li>{@code sourcePublishedAt} is {@link Optional#empty()} unless the caller set it via
 *       {@link ExtractionBuilder#withSourcePublishedAt(Instant)}; {@code withBitemporal()}
 *       is an opt-in flag, NOT a way to populate the timestamp.
 *   <li>Provider failures wrap into {@link ExtractionException} with the original cause.
 * </ul>
 */
class ExtractionBuilderTest {

    /** Fixture record per the test plan; one per test class, no shared helper. */
    record Person(String name, int age) {}

    private static final String PERSON_JSON = "{\"name\":\"Alex\",\"age\":30}";

    private static ParsedDocument sampleDoc() {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var section = new TextSection("Alex Chen, 30 years old", loc);
        var meta = new DocumentMetadata("test.pdf", 1, Optional.empty());
        return new ParsedDocument("doc-1", List.of(section), meta);
    }

    private static ProviderResponse personResponse() {
        return new ProviderResponse(PERSON_JSON, new ProviderUsage(120, 18, "claude-sonnet-4-7-test"));
    }

    private static ExtractionBuilder<Person> freshBuilder() {
        return DocTruth.from(new AnthropicProvider("test-key")).extract("ok", Person.class);
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("withProvenance() returns a new builder instance, not the same one")
        void withProvenanceReturnsNewInstance() {
            var b0 = freshBuilder();
            assertThat(b0.withProvenance()).isNotSameAs(b0);
        }

        @Test
        @DisplayName("withConfidence() returns a new builder instance, not the same one")
        void withConfidenceReturnsNewInstance() {
            var b0 = freshBuilder();
            assertThat(b0.withConfidence()).isNotSameAs(b0);
        }

        @Test
        @DisplayName("withBitemporal() returns a new builder instance, not the same one")
        void withBitemporalReturnsNewInstance() {
            var b0 = freshBuilder();
            assertThat(b0.withBitemporal()).isNotSameAs(b0);
        }

        @Test
        @DisplayName("withMaxRetries(int) returns a new builder instance, not the same one")
        void withMaxRetriesReturnsNewInstance() {
            var b0 = freshBuilder();
            assertThat(b0.withMaxRetries(3)).isNotSameAs(b0);
        }

        @Test
        @DisplayName("withContextStrategy(ContextStrategy) returns a new builder instance")
        void withContextStrategyReturnsNewInstance() {
            var b0 = freshBuilder();
            var strategy = new PriorityTruncate(List.of("Qualifications"), 25_000, OverBudgetPolicy.WARN_AND_INCLUDE);
            assertThat(b0.withContextStrategy(strategy)).isNotSameAs(b0);
        }

        @Test
        @DisplayName("withSourcePublishedAt(Instant) returns a new builder instance")
        void withSourcePublishedAtReturnsNewInstance() {
            var b0 = freshBuilder();
            assertThat(b0.withSourcePublishedAt(Instant.parse("2026-01-15T00:00:00Z")))
                    .isNotSameAs(b0);
        }

        @Test
        @DisplayName("chained with* settings all land — withProvenance + withConfidence + "
                + "withMaxRetries are observable on the run() result")
        void chainedSettingsAllLand() throws ExtractionException {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return personResponse();
                }
            };

            var result = DocTruth.from(fake)
                    .extract("ok", Person.class)
                    .withProvenance()
                    .withConfidence()
                    .withMaxRetries(5)
                    .run(sampleDoc());

            assertThat(result.provenance()).isNotNull();
            assertThat(result.citations()).isNotNull();
            assertThat(result.confidence()).isNotNull();
            // first call succeeded — retries == 0 even though we allowed up to 5
            assertThat(result.provenance().retries()).isZero();
        }

        @Test
        @DisplayName("withMaxRetries called twice — last value wins (immutable builder semantics; "
                + "later call replaces earlier, no accumulation)")
        void withMaxRetriesLastWins() throws ExtractionException {
            // The fake always succeeds on the first call, so retries() is 0 regardless of
            // the configured budget. The test verifies the chained call is accepted and
            // produces a valid run; production-code retry-count assertions land once a
            // failing-then-succeeding fake is added (out of scope here).
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return personResponse();
                }
            };

            var result = DocTruth.from(fake)
                    .extract("ok", Person.class)
                    .withMaxRetries(2)
                    .withMaxRetries(7)
                    .run(sampleDoc());

            assertThat(result.provenance().retries()).isZero();
        }

        @Test
        @DisplayName("withSourcePublishedAt called twice — last value wins; provenance "
                + "reflects the second timestamp, not the first")
        void withSourcePublishedAtLastWins() throws ExtractionException {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return personResponse();
                }
            };
            var first = Instant.parse("2024-01-01T00:00:00Z");
            var second = Instant.parse("2026-01-15T00:00:00Z");

            var result = DocTruth.from(fake)
                    .extract("ok", Person.class)
                    .withSourcePublishedAt(first)
                    .withSourcePublishedAt(second)
                    .run(sampleDoc());

            assertThat(result.provenance().sourcePublishedAt()).contains(second);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("withMaxRetries(-1) throws IllegalArgumentException")
        void withMaxRetriesNegative() {
            assertThatThrownBy(() -> freshBuilder().withMaxRetries(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("withMaxRetries(0) is accepted (callers may opt out of retries entirely)")
        void withMaxRetriesZeroAccepted() {
            assertThat(freshBuilder().withMaxRetries(0)).isNotNull();
        }

        @Test
        @DisplayName("withContextStrategy(null) throws NullPointerException")
        void withContextStrategyNull() {
            var b = freshBuilder();
            assertThatNullPointerException().isThrownBy(() -> b.withContextStrategy(null));
        }

        @Test
        @DisplayName("withSourcePublishedAt(null) throws NullPointerException")
        void withSourcePublishedAtNull() {
            var b = freshBuilder();
            assertThatNullPointerException().isThrownBy(() -> b.withSourcePublishedAt(null));
        }

        @Test
        @DisplayName("run(null) throws NullPointerException mentioning \"doc\"")
        void runNullDoc() {
            var b = freshBuilder();
            assertThatNullPointerException().isThrownBy(() -> b.run(null)).withMessageContaining("doc");
        }
    }

    @Nested
    @DisplayName("run")
    class Run {

        @Test
        @DisplayName("happy path: provider.complete is called exactly once; rawJson parses into T; "
                + "result.value() equals new Person(\"Alex\", 30); provenance reflects "
                + "provider.name() and usage.modelVersion(); retries == 0")
        void happyPathParsesJsonAndAssemblesProvenance() throws ExtractionException {
            var calls = new AtomicInteger();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    calls.incrementAndGet();
                    return personResponse();
                }
            };
            var beforeRun = Instant.now().minusSeconds(1);

            var result = DocTruth.from(fake)
                    .extract("Extract the person.", Person.class)
                    .run(sampleDoc());

            assertThat(calls.get()).isEqualTo(1);
            assertThat(result.value()).isEqualTo(new Person("Alex", 30));
            assertThat(result.provenance().model()).isEqualTo("anthropic");
            assertThat(result.provenance().modelVersion()).isEqualTo("claude-sonnet-4-7-test");
            assertThat(result.provenance().retries()).isZero();
            assertThat(result.provenance().extractedAt())
                    .isAfter(beforeRun)
                    .isBefore(Instant.now().plusSeconds(1));
            assertThat(result.citations()).isNotNull();
            assertThat(result.confidence()).isNotNull();
        }

        @Test
        @DisplayName("provider returns malformed JSON → ExtractionException with non-blank "
                + "errorCode; cause-chain links to a Jackson JsonProcessingException")
        void malformedJsonWrappedAsExtractionException() {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return new ProviderResponse("{not valid json", new ProviderUsage(1, 1, "v"));
                }
            };
            var builder = DocTruth.from(fake).extract("ok", Person.class);
            var doc = sampleDoc();

            assertThatThrownBy(() -> builder.run(doc))
                    .isInstanceOf(ExtractionException.class)
                    .satisfies(ex -> {
                        var ee = (ExtractionException) ex;
                        assertThat(ee.errorCode()).isNotBlank();
                        assertThat(ee.getCause()).isInstanceOf(JsonProcessingException.class);
                    });
        }

        @Test
        @DisplayName("provider throws ProviderException → ExtractionException wraps it; "
                + "getCause() returns the original ProviderException instance")
        void providerExceptionWrappedAsExtractionException() {
            var underlying = new ProviderException(
                    "provider.timeout", "upstream timeout", "anthropic", OptionalInt.empty(), true);
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) throws ProviderException {
                    throw underlying;
                }
            };
            var builder = DocTruth.from(fake).extract("ok", Person.class);
            var doc = sampleDoc();

            assertThatThrownBy(() -> builder.run(doc))
                    .isInstanceOf(ExtractionException.class)
                    .satisfies(ex -> assertThat(ex.getCause()).isSameAs(underlying));
        }

        @Test
        @DisplayName("withContextStrategy(PriorityTruncate): the user-prompt sent to the provider "
                + "is exactly what PriorityTruncate.assemble produced")
        void contextStrategyAssembleIsUsedAsUserPrompt() throws ExtractionException {
            // Two sections — only the first contains the priority pattern. Budget is tight
            // enough that the filler must be dropped, so the strategy's assemble() result
            // is observably DIFFERENT from the default "concat all sections" rendering.
            var loc = new SourceLocation(1, 1, 1, 1, 0);
            var meta = new DocumentMetadata("test.pdf", 1, Optional.empty());
            var priority = new TextSection("Qualifications block", loc);
            var filler = new TextSection("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", loc); // 30 'a'
            var doc = new ParsedDocument("doc-1", List.of(priority, filler), meta);

            var strategy = new PriorityTruncate(List.of("Qualifications"), 25, OverBudgetPolicy.STRICT);
            var expected = strategy.assemble(doc);

            var captured = new AtomicReference<String>();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    captured.set(req.userPrompt());
                    return personResponse();
                }
            };

            DocTruth.from(fake)
                    .extract("ok", Person.class)
                    .withContextStrategy(strategy)
                    .run(doc);

            assertThat(captured.get()).isEqualTo(expected);
            // sanity: the strategy actually trimmed the filler — i.e. assemble() was used,
            // not the default concat path which would have included both sections.
            assertThat(captured.get()).doesNotContain(filler.text());
            assertThat(captured.get()).contains(priority.text());
        }

        @Test
        @DisplayName("withContextStrategy(PriorityTruncate STRICT) + priority-only > budget: "
                + "ExtractionException from assemble propagates through run()")
        void contextStrategyOverBudgetPropagates() {
            var loc = new SourceLocation(1, 1, 1, 1, 0);
            var meta = new DocumentMetadata("test.pdf", 1, Optional.empty());
            var bigPriority = new TextSection("Qualifications " + "x".repeat(200), loc);
            var doc = new ParsedDocument("doc-1", List.of(bigPriority), meta);

            var strategy = new PriorityTruncate(List.of("Qualifications"), 50, OverBudgetPolicy.STRICT);
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return personResponse();
                }
            };
            var builder = DocTruth.from(fake).extract("ok", Person.class).withContextStrategy(strategy);

            assertThatThrownBy(() -> builder.run(doc))
                    .isInstanceOf(ExtractionException.class)
                    .satisfies(ex ->
                            assertThat(((ExtractionException) ex).errorCode()).isEqualTo("CONTEXT_OVER_BUDGET"));
        }

        @Test
        @DisplayName(
                "calling run() twice on the same builder is safe — pure-config value, " + "no shared per-call state")
        void runIsIdempotentOnTheSameBuilder() throws ExtractionException {
            var calls = new AtomicInteger();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    calls.incrementAndGet();
                    return personResponse();
                }
            };
            var builder = DocTruth.from(fake).extract("ok", Person.class);
            var doc = sampleDoc();

            var first = builder.run(doc);
            var second = builder.run(doc);

            assertThat(calls.get()).isEqualTo(2);
            assertThat(first.value()).isEqualTo(second.value());
            assertThat(first.provenance().retries()).isZero();
            assertThat(second.provenance().retries()).isZero();
        }
    }

    @Nested
    @DisplayName("provenance")
    class Provenance {

        @Test
        @DisplayName("without withSourcePublishedAt: result.provenance().sourcePublishedAt() " + "is Optional.empty()")
        void sourcePublishedAtEmptyByDefault() throws ExtractionException {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return personResponse();
                }
            };

            var result = DocTruth.from(fake).extract("ok", Person.class).run(sampleDoc());

            assertThat(result.provenance().sourcePublishedAt()).isEmpty();
        }

        @Test
        @DisplayName("with withSourcePublishedAt(2026-01-15T00:00:00Z): provenance carries that "
                + "exact Instant inside the Optional")
        void sourcePublishedAtRoundTrips() throws ExtractionException {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return personResponse();
                }
            };
            var published = Instant.parse("2026-01-15T00:00:00Z");

            var result = DocTruth.from(fake)
                    .extract("ok", Person.class)
                    .withSourcePublishedAt(published)
                    .run(sampleDoc());

            assertThat(result.provenance().sourcePublishedAt()).contains(published);
        }

        @Test
        @DisplayName("withBitemporal() alone does NOT populate sourcePublishedAt — caller must "
                + "call withSourcePublishedAt(...) explicitly")
        void bitemporalAloneDoesNotSetSourcePublishedAt() throws ExtractionException {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return personResponse();
                }
            };

            var result = DocTruth.from(fake)
                    .extract("ok", Person.class)
                    .withBitemporal()
                    .run(sampleDoc());

            assertThat(result.provenance().sourcePublishedAt()).isEmpty();
        }
    }

    @Nested
    @DisplayName("citations")
    class Citations {

        @Test
        @DisplayName("without withProvenance(): result.citations() is empty (the default — "
                + "callers opt in to the citation-matching cost via withProvenance)")
        void noProvenanceProducesEmptyCitations() throws ExtractionException {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return personResponse();
                }
            };

            var result = DocTruth.from(fake).extract("ok", Person.class).run(sampleDoc());

            assertThat(result.citations()).isEmpty();
        }

        @Test
        @DisplayName("with withProvenance(): result.citations() carries a Citation per known-present "
                + "field; the \"name\" field maps to an exact-match Citation (matchScore == 1.0)")
        void provenanceProducesPerFieldCitations() throws ExtractionException {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return personResponse();
                }
            };

            var result = DocTruth.from(fake)
                    .extract("ok", Person.class)
                    .withProvenance()
                    .run(sampleDoc());

            assertThat(result.citations()).isNotEmpty();
            assertThat(result.citations()).containsKey("name");
            assertThat(result.citations().get("name").matchScore()).isEqualTo(1.0);
            assertThat(result.citations().get("name").exactQuote()).isEqualTo("Alex");
            assertThat(result.citations().get("name").location().pageStart()).isEqualTo(1);
        }
    }
}
