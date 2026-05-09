package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JavaNativeExtractionContractTest {

    record VersionedContract(String partyA, int revision) {}

    record OptionalContract(String partyA, Optional<String> note) {}

    record Payment(int revision) {}

    record ContractWithPayment(String partyA, Payment payment) {}

    record OptionalPaymentContract(String partyA, Optional<Payment> payment) {}

    private static ParsedDocument contractDoc() {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var section = new TextSection("Acme signed contract revision 7.", loc);
        var meta = new DocumentMetadata("contract.pdf", 1, Optional.empty());
        return new ParsedDocument("doc-contract", List.of(section), meta);
    }

    @Test
    @DisplayName("missing Java required fields are schema-validated and retried before typed deserialization")
    void missingJavaRequiredFieldIsRetriedBeforeDeserialization() throws ExtractionException {
        var calls = new AtomicInteger();
        LlmProvider fake = new AnthropicProvider("test-key") {
            @Override
            public ProviderResponse complete(ProviderRequest req) {
                int attempt = calls.incrementAndGet();
                if (attempt == 1) {
                    return new ProviderResponse("{\"partyA\":\"Acme\"}", new ProviderUsage(1, 1, "v"));
                }
                return new ProviderResponse("{\"partyA\":\"Acme\",\"revision\":7}", new ProviderUsage(1, 1, "v"));
            }
        };

        var result = DocTruth.from(fake)
                .extract("ok", VersionedContract.class)
                .withMaxRetries(1)
                .run(contractDoc());

        assertThat(calls.get()).isEqualTo(2);
        assertThat(result.value()).isEqualTo(new VersionedContract("Acme", 7));
        assertThat(result.provenance().retries()).isEqualTo(1);
    }

    @Test
    @DisplayName("Optional<T> Java fields deserialize as Optional.empty when absent and Optional.of when present")
    void optionalJavaFieldDeserializesThroughTypedExtraction() throws ExtractionException {
        var calls = new AtomicInteger();
        LlmProvider fake = new AnthropicProvider("test-key") {
            @Override
            public ProviderResponse complete(ProviderRequest req) {
                int attempt = calls.incrementAndGet();
                if (attempt == 1) {
                    return new ProviderResponse("{\"partyA\":\"Acme\"}", new ProviderUsage(1, 1, "v"));
                }
                return new ProviderResponse("{\"partyA\":\"Acme\",\"note\":\"signed\"}", new ProviderUsage(1, 1, "v"));
            }
        };
        var builder = DocTruth.from(fake).extract("ok", OptionalContract.class);

        var absent = builder.run(contractDoc());
        var present = builder.run(contractDoc());

        assertThat(absent.value()).isEqualTo(new OptionalContract("Acme", Optional.empty()));
        assertThat(present.value()).isEqualTo(new OptionalContract("Acme", Optional.of("signed")));
    }

    @Test
    @DisplayName("non-Optional Java reference fields reject null before typed deserialization")
    void nonOptionalReferenceNullIsSchemaValidationFailure() {
        LlmProvider fake = new AnthropicProvider("test-key") {
            @Override
            public ProviderResponse complete(ProviderRequest req) {
                return new ProviderResponse("{\"partyA\":\"Acme\",\"payment\":null}", new ProviderUsage(1, 1, "v"));
            }
        };

        assertThatThrownBy(() -> DocTruth.from(fake)
                        .extract("ok", ContractWithPayment.class)
                        .run(contractDoc()))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("payment expected object but got null");
    }

    @Test
    @DisplayName("Optional Java reference fields accept explicit null as Optional.empty")
    void optionalReferenceNullMapsToEmpty() throws ExtractionException {
        LlmProvider fake = new AnthropicProvider("test-key") {
            @Override
            public ProviderResponse complete(ProviderRequest req) {
                return new ProviderResponse("{\"partyA\":\"Acme\",\"payment\":null}", new ProviderUsage(1, 1, "v"));
            }
        };

        var result =
                DocTruth.from(fake).extract("ok", OptionalPaymentContract.class).run(contractDoc());

        assertThat(result.value()).isEqualTo(new OptionalPaymentContract("Acme", Optional.empty()));
    }
}
