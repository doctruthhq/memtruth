package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import ai.doctruth.spi.AuditEvent;
import ai.doctruth.spi.AuditEventListener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPI integration tests for {@link ExtractionBuilder}: kept in a sibling file so
 * {@link ExtractionBuilderTest} stays under the CONTRIBUTING.md "no god files" 500-LOC test cap.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>{@link ExtractionBuilder#withAuditListener(AuditEventListener)} — emits an
 *       {@code extraction.success} or {@code extraction.failed} event around every run.
 *   <li>{@link Provenance#region()} — populated from {@link LlmProvider#region()} on every
 *       run, default {@code Optional.empty()} for the four built-in providers.
 * </ul>
 */
class ExtractionBuilderSpiTest {

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

    private static AnthropicProvider succeedingFake() {
        return new AnthropicProvider("test-key") {
            @Override
            public ProviderResponse complete(ProviderRequest req) {
                return personResponse();
            }
        };
    }

    @Nested
    @DisplayName("audit listener (SPI)")
    class AuditListener {

        @Test
        @DisplayName("withAuditListener: listener captures one extraction.success event on the happy "
                + "path with provider + modelVersion + retries attributes")
        void capturesSuccess() throws ExtractionException {
            List<AuditEvent> captured = new ArrayList<>();
            AuditEventListener listener = captured::add;

            DocTruth.from(succeedingFake())
                    .extract("ok", Person.class)
                    .withAuditListener(listener)
                    .run(sampleDoc());

            assertThat(captured).hasSize(1);
            var event = captured.get(0);
            assertThat(event.kind()).isEqualTo("extraction.success");
            assertThat(event.attributes())
                    .containsEntry("provider", "anthropic")
                    .containsEntry("modelVersion", "claude-sonnet-4-7-test")
                    .containsEntry("retries", "0");
        }

        @Test
        @DisplayName("withAuditListener: listener captures one extraction.failed event when provider "
                + "throws, AND the original ExtractionException still propagates")
        void capturesFailureAndStillThrows() {
            var underlying = new ProviderException(
                    "provider.timeout", "upstream timeout", "anthropic", OptionalInt.empty(), true);
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) throws ProviderException {
                    throw underlying;
                }
            };
            List<AuditEvent> captured = new ArrayList<>();
            AuditEventListener listener = captured::add;
            var builder = DocTruth.from(fake).extract("ok", Person.class).withAuditListener(listener);
            var doc = sampleDoc();

            assertThatThrownBy(() -> builder.run(doc)).isInstanceOf(ExtractionException.class);

            assertThat(captured).hasSize(1);
            var event = captured.get(0);
            assertThat(event.kind()).isEqualTo("extraction.failed");
            assertThat(event.attributes())
                    .containsEntry("provider", "anthropic")
                    .containsKey("errorCode");
            assertThat(event.attributes().get("errorCode")).isNotBlank();
        }

        @Test
        @DisplayName("default NOOP listener: run() succeeds without throwing or interfering when "
                + "withAuditListener was never called")
        void defaultNoopDoesNotInterfere() throws ExtractionException {
            var result =
                    DocTruth.from(succeedingFake()).extract("ok", Person.class).run(sampleDoc());

            assertThat(result.value()).isEqualTo(new Person("Alex", 30));
        }

        @Test
        @DisplayName("withAuditListener(null) throws NullPointerException")
        void nullRejected() {
            var b = DocTruth.from(succeedingFake()).extract("ok", Person.class);
            assertThatNullPointerException().isThrownBy(() -> b.withAuditListener(null));
        }
    }

    @Nested
    @DisplayName("provider region propagation")
    class RegionPropagation {

        @Test
        @DisplayName("default fake provider (no region override): result.provenance().region() "
                + "is Optional.empty() — the OSS default")
        void defaultEmpty() throws ExtractionException {
            var result =
                    DocTruth.from(succeedingFake()).extract("ok", Person.class).run(sampleDoc());

            assertThat(result.provenance().region()).isEmpty();
        }

        @Test
        @DisplayName("anonymous subclass overriding region() to ap-southeast-2: "
                + "result.provenance().region() carries 'ap-southeast-2'")
        void overrideRoundTrips() throws ExtractionException {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return personResponse();
                }

                @Override
                public Optional<String> region() {
                    return Optional.of("ap-southeast-2");
                }
            };

            var result = DocTruth.from(fake).extract("ok", Person.class).run(sampleDoc());

            assertThat(result.provenance().region()).contains("ap-southeast-2");
        }
    }
}
