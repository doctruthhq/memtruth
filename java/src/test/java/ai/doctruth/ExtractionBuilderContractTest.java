package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for extraction behaviours that must not regress into smoke-only
 * assertions: schema shape, validation retries, and confidence output.
 */
class ExtractionBuilderContractTest {

    record Person(String name, int age) {}

    record Contract(String partyA, String partyB, BigDecimal totalValue) {}

    record Payment(BigDecimal totalValue) {}

    record ContractWithPayment(String partyA, Payment payment) {}

    record ContractWithOptionalPayment(String partyA, Optional<Payment> payment) {}

    static final class LegacyContract {
        public String partyA;
    }

    private static final String PERSON_JSON = "{\"name\":\"Alex\",\"age\":30}";

    private static final String VALID_CONTRACT_JSON = "{\"partyA\":\"Acme\",\"partyB\":\"Beta\",\"totalValue\":1000}";

    private static ParsedDocument sampleDoc() {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var section = new TextSection("Alex Chen, 30 years old", loc);
        var meta = new DocumentMetadata("test.pdf", 1, Optional.empty());
        return new ParsedDocument("doc-1", List.of(section), meta);
    }

    private static ProviderResponse personResponse() {
        return new ProviderResponse(PERSON_JSON, new ProviderUsage(120, 18, "claude-sonnet-4-7-test"));
    }

    private static ParsedDocument contractDoc() {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var section = new TextSection("Acme agrees to pay Beta 1000.", loc);
        var meta = new DocumentMetadata("contract.pdf", 1, Optional.empty());
        return new ParsedDocument("doc-contract", List.of(section), meta);
    }

    private static ProviderResponse contractResponse(String rawJson) {
        return new ProviderResponse(rawJson, new ProviderUsage(120, 18, "claude-sonnet-4-7-test"));
    }

    @Nested
    @DisplayName("schema")
    class Schema {

        @Test
        @DisplayName("ProviderRequest.responseSchema describes the requested record fields, not an empty object")
        void responseSchemaDescribesTargetRecord() throws ExtractionException {
            var captured = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    captured.set(req.responseSchema());
                    return personResponse();
                }
            };

            DocTruth.from(fake).extract("ok", Person.class).run(sampleDoc());

            assertThat(captured.get().path("type").asText()).isEqualTo("object");
            assertThat(captured.get()
                            .path("properties")
                            .path("name")
                            .path("type")
                            .asText())
                    .isEqualTo("string");
            assertThat(captured.get()
                            .path("properties")
                            .path("age")
                            .path("type")
                            .asText())
                    .isEqualTo("integer");
            assertThat(captured.get().path("required").toString()).contains("name", "age");
        }
    }

    @Nested
    @DisplayName("retry")
    class Retry {

        @Test
        @DisplayName("malformed JSON is retried up to withMaxRetries and provenance records validation retries")
        void malformedJsonIsRetriedAndCounted() throws ExtractionException {
            var calls = new AtomicInteger();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    int attempt = calls.incrementAndGet();
                    if (attempt == 1) {
                        return new ProviderResponse("{not valid json", new ProviderUsage(1, 1, "v"));
                    }
                    return personResponse();
                }
            };

            var result = DocTruth.from(fake)
                    .extract("ok", Person.class)
                    .withMaxRetries(1)
                    .run(sampleDoc());

            assertThat(calls.get()).isEqualTo(2);
            assertThat(result.value()).isEqualTo(new Person("Alex", 30));
            assertThat(result.provenance().retries()).isEqualTo(1);
        }

        @Test
        @DisplayName("withProvenance(): missing field evidence is retried before returning citations")
        void missingEvidenceIsRetriedAndCounted() throws ExtractionException {
            var calls = new AtomicInteger();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    int attempt = calls.incrementAndGet();
                    if (attempt == 1) {
                        return new ProviderResponse(
                                "{\"name\":\"zzzzzzzzzzzzzz\",\"age\":30}", new ProviderUsage(1, 1, "v"));
                    }
                    return personResponse();
                }
            };

            var result = DocTruth.from(fake)
                    .extract("ok", Person.class)
                    .withProvenance()
                    .withMaxRetries(1)
                    .run(sampleDoc());

            assertThat(calls.get()).isEqualTo(2);
            assertThat(result.value()).isEqualTo(new Person("Alex", 30));
            assertThat(result.citations().get("name").matchScore()).isEqualTo(1.0);
            assertThat(result.provenance().retries()).isEqualTo(1);
        }

        @Test
        @DisplayName("withProvenance(): low-score fuzzy evidence is surfaced instead of retried")
        void lowScoreEvidenceIsReturnedForCallerAudit() throws ExtractionException {
            var calls = new AtomicInteger();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    calls.incrementAndGet();
                    return new ProviderResponse(
                            "{\"name\":\"Missing Person\",\"age\":30}", new ProviderUsage(1, 1, "v"));
                }
            };

            var result = DocTruth.from(fake)
                    .extract("ok", Person.class)
                    .withProvenance()
                    .withMaxRetries(1)
                    .run(sampleDoc());

            assertThat(calls.get()).isEqualTo(1);
            assertThat(result.citations().get("name").matchScore()).isBetween(0.0, 0.85);
            assertThat(result.provenance().retries()).isZero();
        }
    }

    @Nested
    @DisplayName("confidence")
    class ConfidenceScores {

        @Test
        @DisplayName("withConfidence(): result.confidence() carries a score per extracted field")
        void confidenceProducesPerFieldScores() throws ExtractionException {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return personResponse();
                }
            };

            var result = DocTruth.from(fake)
                    .extract("ok", Person.class)
                    .withConfidence()
                    .run(sampleDoc());

            assertThat(result.confidence()).containsKeys("name", "age");
            assertThat(result.confidence().get("name").score()).isEqualTo(1.0);
            assertThat(result.confidence().get("age").score()).isEqualTo(1.0);
            assertThat(result.confidence().get("name").rationale()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("custom constraints")
    class CustomConstraints {

        @Test
        @DisplayName("field constraint failure reports the field path, message, and stable error code")
        void fieldConstraintFailureReportsPathAndMessage() {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return contractResponse("{\"partyA\":\"Acme\",\"partyB\":\"Beta\",\"totalValue\":-1}");
                }
            };

            assertThatThrownBy(() -> DocTruth.from(fake)
                            .extract("ok", Contract.class)
                            .withFieldConstraint(
                                    "totalValue", BigDecimal.class, value -> value.signum() > 0, "must be positive")
                            .run(contractDoc()))
                    .isInstanceOf(ExtractionException.class)
                    .hasMessageContaining("totalValue")
                    .hasMessageContaining("must be positive")
                    .satisfies(ex -> assertThat(((ExtractionException) ex).errorCode())
                            .isEqualTo("EXTRACTION_CONSTRAINT_FAILED"));
        }

        @Test
        @DisplayName("object constraint failure can validate cross-field business rules")
        void objectConstraintFailureReportsCrossFieldRule() {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return contractResponse("{\"partyA\":\"Acme\",\"partyB\":\"Acme\",\"totalValue\":1000}");
                }
            };

            assertThatThrownBy(() -> DocTruth.from(fake)
                            .extract("ok", Contract.class)
                            .withObjectConstraint(
                                    contract -> !contract.partyA().equals(contract.partyB()),
                                    "partyA and partyB must differ")
                            .run(contractDoc()))
                    .isInstanceOf(ExtractionException.class)
                    .hasMessageContaining("$")
                    .hasMessageContaining("partyA and partyB must differ")
                    .satisfies(ex -> assertThat(((ExtractionException) ex).errorCode())
                            .isEqualTo("EXTRACTION_CONSTRAINT_FAILED"));
        }

        @Test
        @DisplayName("constraint failures are retried and provenance records the successful validation retry")
        void constraintFailureIsRetriedAndCounted() throws ExtractionException {
            var calls = new AtomicInteger();
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    int attempt = calls.incrementAndGet();
                    if (attempt == 1) {
                        return contractResponse("{\"partyA\":\"Acme\",\"partyB\":\"Beta\",\"totalValue\":-1}");
                    }
                    return contractResponse(VALID_CONTRACT_JSON);
                }
            };

            var result = DocTruth.from(fake)
                    .extract("ok", Contract.class)
                    .withFieldConstraint(
                            "totalValue", BigDecimal.class, value -> value.signum() > 0, "must be positive")
                    .withMaxRetries(1)
                    .run(contractDoc());

            assertThat(calls.get()).isEqualTo(2);
            assertThat(result.value()).isEqualTo(new Contract("Acme", "Beta", BigDecimal.valueOf(1000)));
            assertThat(result.provenance().retries()).isEqualTo(1);
        }

        @Test
        @DisplayName("nested field constraint can validate dotted record paths")
        void nestedFieldConstraintValidatesDottedPath() throws ExtractionException {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return contractResponse("{\"partyA\":\"Acme\",\"payment\":{\"totalValue\":1000}}");
                }
            };

            var result = DocTruth.from(fake)
                    .extract("ok", ContractWithPayment.class)
                    .withFieldConstraint(
                            "payment.totalValue",
                            BigDecimal.class,
                            value -> value.compareTo(BigDecimal.valueOf(500)) > 0,
                            "must exceed threshold")
                    .run(contractDoc());

            assertThat(result.value())
                    .isEqualTo(new ContractWithPayment("Acme", new Payment(BigDecimal.valueOf(1000))));
        }

        @Test
        @DisplayName("nested field constraint can handle an absent Optional intermediate record")
        void nestedFieldConstraintHandlesAbsentOptionalIntermediate() throws ExtractionException {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return contractResponse("{\"partyA\":\"Acme\"}");
                }
            };

            var result = DocTruth.from(fake)
                    .extract("ok", ContractWithOptionalPayment.class)
                    .withFieldConstraint(
                            "payment.totalValue", BigDecimal.class, value -> value == null, "must be absent")
                    .run(contractDoc());

            assertThat(result.value()).isEqualTo(new ContractWithOptionalPayment("Acme", Optional.empty()));
        }

        @Test
        @DisplayName("invalid field path fails with stable constraint error")
        void invalidFieldPathReportsStableError() {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return contractResponse(VALID_CONTRACT_JSON);
                }
            };

            assertThatThrownBy(() -> DocTruth.from(fake)
                            .extract("ok", Contract.class)
                            .withFieldConstraint(
                                    "missing",
                                    String.class,
                                    value -> value != null && !value.isBlank(),
                                    "must be present")
                            .run(contractDoc()))
                    .isInstanceOf(ExtractionException.class)
                    .hasMessageContaining("field path not found: missing")
                    .satisfies(ex -> assertThat(((ExtractionException) ex).errorCode())
                            .isEqualTo("EXTRACTION_CONSTRAINT_FAILED"));
        }

        @Test
        @DisplayName("field constraint on non-record target fails with stable constraint error")
        void fieldConstraintOnNonRecordTargetReportsStableError() {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return contractResponse("{\"partyA\":\"Acme\"}");
                }
            };

            assertThatThrownBy(() -> DocTruth.from(fake)
                            .extract("ok", LegacyContract.class)
                            .withFieldConstraint(
                                    "partyA",
                                    String.class,
                                    value -> value != null && !value.isBlank(),
                                    "must be present")
                            .run(contractDoc()))
                    .isInstanceOf(ExtractionException.class)
                    .hasMessageContaining("field path requires record target: partyA")
                    .satisfies(ex -> assertThat(((ExtractionException) ex).errorCode())
                            .isEqualTo("EXTRACTION_CONSTRAINT_FAILED"));
        }

        @Test
        @DisplayName("field constraint type mismatch fails with stable constraint error")
        void fieldConstraintTypeMismatchReportsStableError() {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return contractResponse(VALID_CONTRACT_JSON);
                }
            };

            assertThatThrownBy(() -> DocTruth.from(fake)
                            .extract("ok", Contract.class)
                            .withFieldConstraint(
                                    "totalValue",
                                    String.class,
                                    value -> value != null && !value.isBlank(),
                                    "must be text")
                            .run(contractDoc()))
                    .isInstanceOf(ExtractionException.class)
                    .hasMessageContaining("constraint failed at totalValue")
                    .hasMessageContaining(BigDecimal.class.getName())
                    .satisfies(ex -> assertThat(((ExtractionException) ex).errorCode())
                            .isEqualTo("EXTRACTION_CONSTRAINT_FAILED"));
        }

        @Test
        @DisplayName("field constraint predicate exceptions are wrapped with constraint context")
        void fieldConstraintPredicateExceptionIsWrapped() {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return contractResponse(VALID_CONTRACT_JSON);
                }
            };

            assertThatThrownBy(() -> DocTruth.from(fake)
                            .extract("ok", Contract.class)
                            .withFieldConstraint(
                                    "totalValue",
                                    BigDecimal.class,
                                    value -> {
                                        throw new IllegalStateException("bad rule");
                                    },
                                    "must be valid")
                            .run(contractDoc()))
                    .isInstanceOf(ExtractionException.class)
                    .hasMessageContaining("constraint evaluation failed at totalValue")
                    .hasMessageContaining("bad rule")
                    .satisfies(ex -> assertThat(((ExtractionException) ex).errorCode())
                            .isEqualTo("EXTRACTION_CONSTRAINT_FAILED"));
        }

        @Test
        @DisplayName("object constraint predicate exceptions are wrapped with root context")
        void objectConstraintPredicateExceptionIsWrapped() {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return contractResponse(VALID_CONTRACT_JSON);
                }
            };

            assertThatThrownBy(() -> DocTruth.from(fake)
                            .extract("ok", Contract.class)
                            .withObjectConstraint(
                                    contract -> {
                                        throw new IllegalStateException("cross-field rule crashed");
                                    },
                                    "must be valid")
                            .run(contractDoc()))
                    .isInstanceOf(ExtractionException.class)
                    .hasMessageContaining("constraint evaluation failed at $")
                    .hasMessageContaining("cross-field rule crashed")
                    .satisfies(ex -> assertThat(((ExtractionException) ex).errorCode())
                            .isEqualTo("EXTRACTION_CONSTRAINT_FAILED"));
        }

        @Test
        @DisplayName("object constraint success does not block extraction")
        void objectConstraintSuccessDoesNotBlockExtraction() throws ExtractionException {
            LlmProvider fake = new AnthropicProvider("test-key") {
                @Override
                public ProviderResponse complete(ProviderRequest req) {
                    return contractResponse(VALID_CONTRACT_JSON);
                }
            };

            var result = DocTruth.from(fake)
                    .extract("ok", Contract.class)
                    .withObjectConstraint(
                            contract -> !contract.partyA().equals(contract.partyB()), "partyA and partyB must differ")
                    .run(contractDoc());

            assertThat(result.value()).isEqualTo(new Contract("Acme", "Beta", BigDecimal.valueOf(1000)));
        }
    }
}
