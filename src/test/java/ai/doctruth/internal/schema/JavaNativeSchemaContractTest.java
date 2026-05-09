package ai.doctruth.internal.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JavaNativeSchemaContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    enum ContractStatus {
        DRAFT,
        SIGNED
    }

    record Party(String legalName) {}

    record Contract(
            @JsonProperty("party_a") Party partyA,
            LocalDate effectiveDate,
            BigDecimal totalValue,
            List<String> tags,
            Map<String, BigDecimal> fees,
            ContractStatus status,
            boolean active,
            int revision,
            Integer riskScore,
            @JsonIgnore String internalNote) {}

    record OptionalNotes(String partyA, Optional<String> note) {}

    record FormattedDate(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate signedAt) {}

    static final class PojoInvoice {
        public String invoiceId;
        public BigDecimal total;
    }

    record VersionedContract(String partyA, int revision) {}

    record UnsupportedShape(Object payload) {}

    record UnsupportedMap(Map<String, Object> payload) {}

    record UnsupportedWildcard(List<?> items) {}

    @Test
    @DisplayName("record schema covers nested objects, collections, maps, enums, dates, numbers, and annotations")
    void recordSchemaCoversJavaNativeShapes() {
        JsonNode schema = JsonSchemaBuilder.forType(Contract.class);

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "party_a",
                        "effectiveDate",
                        "totalValue",
                        "tags",
                        "fees",
                        "status",
                        "active",
                        "revision",
                        "riskScore");

        JsonNode properties = schema.path("properties");
        assertThat(properties.has("internalNote")).isFalse();
        assertThat(properties.path("party_a").path("type").asText()).isEqualTo("object");
        assertThat(properties
                        .path("party_a")
                        .path("properties")
                        .path("legalName")
                        .path("type")
                        .asText())
                .isEqualTo("string");
        assertThat(properties.path("effectiveDate").path("type").asText()).isEqualTo("string");
        assertThat(properties.path("effectiveDate").path("format").asText()).isEqualTo("date");
        assertThat(properties.path("totalValue").path("type").asText()).isEqualTo("number");
        assertThat(properties.path("tags").path("items").path("type").asText()).isEqualTo("string");
        assertThat(properties
                        .path("fees")
                        .path("additionalProperties")
                        .path("type")
                        .asText())
                .isEqualTo("number");
        assertThat(properties.path("status").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("DRAFT", "SIGNED");
        assertThat(properties.path("active").path("type").asText()).isEqualTo("boolean");
        assertThat(properties.path("revision").path("type").asText()).isEqualTo("integer");
        assertThat(properties.path("riskScore").path("type").asText()).isEqualTo("integer");
    }

    @Test
    @DisplayName("Optional<T> is omitted from required while preserving the wrapped value schema")
    void optionalRecordComponentIsNotRequired() {
        JsonNode schema = JsonSchemaBuilder.forType(OptionalNotes.class);

        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("partyA");
        assertThat(schema.path("properties").path("note").path("type"))
                .extracting(JsonNode::asText)
                .containsExactly("string", "null");
    }

    @Test
    @DisplayName("LocalDate remains a string date even when callers add JsonFormat")
    void jsonFormatDateUsesStringDateSchema() {
        JsonNode schema = JsonSchemaBuilder.forType(FormattedDate.class);

        assertThat(schema.path("properties").path("signedAt").path("type").asText())
                .isEqualTo("string");
        assertThat(schema.path("properties").path("signedAt").path("format").asText())
                .isEqualTo("date");
    }

    @Test
    @DisplayName("simple POJO classes use the same Java-native schema path as records")
    void pojoClassUsesJavaNativeSchemaPath() {
        JsonNode schema = JsonSchemaBuilder.forType(PojoInvoice.class);

        assertThat(schema.path("properties").path("invoiceId").path("type").asText())
                .isEqualTo("string");
        assertThat(schema.path("properties").path("total").path("type").asText())
                .isEqualTo("number");
        assertThat(schema.path("required"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("invoiceId", "total");
    }

    @Test
    @DisplayName("generated Java schema is accepted by local validation and reports required-field errors")
    void generatedSchemaWorksWithLocalValidator() throws Exception {
        JsonNode schema = JsonSchemaBuilder.forType(VersionedContract.class);

        JsonSchemaValidator.validate(json("{\"partyA\":\"Acme\",\"revision\":7}"), schema, 0);

        assertThatThrownBy(() -> JsonSchemaValidator.validate(json("{\"partyA\":\"Acme\"}"), schema, 0))
                .isInstanceOf(ai.doctruth.ExtractionException.class)
                .hasMessageContaining("revision required field missing");
    }

    @Test
    @DisplayName("non-Optional reference properties reject explicit null values")
    void nonOptionalReferencePropertyRejectsNull() throws Exception {
        JsonNode schema = JsonSchemaBuilder.forType(Contract.class);

        assertThatThrownBy(() -> JsonSchemaValidator.validate(json("""
                                {
                                  "party_a": null,
                                  "effectiveDate": "2026-01-01",
                                  "totalValue": 1000,
                                  "tags": [],
                                  "fees": {},
                                  "status": "SIGNED",
                                  "active": true,
                                  "revision": 1,
                                  "riskScore": 50
                                }
                                """), schema, 0))
                .isInstanceOf(ai.doctruth.ExtractionException.class)
                .hasMessageContaining("party_a expected object but got null");
    }

    @Test
    @DisplayName("Optional reference properties accept absent and explicit null values")
    void optionalReferencePropertyAcceptsAbsentAndNull() throws Exception {
        record MaybeParty(Optional<Party> party) {}
        JsonNode schema = JsonSchemaBuilder.forType(MaybeParty.class);

        JsonSchemaValidator.validate(json("{}"), schema, 0);
        JsonSchemaValidator.validate(json("{\"party\":null}"), schema, 0);
        JsonSchemaValidator.validate(json("{\"party\":{\"legalName\":\"Acme\"}}"), schema, 0);
    }

    @Test
    @DisplayName("raw Object is rejected instead of becoming an unbounded object schema")
    void rawObjectShapeFailsFast() {
        assertThatThrownBy(() -> JsonSchemaBuilder.forType(UnsupportedShape.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload")
                .hasMessageContaining(Object.class.getName());
    }

    @Test
    @DisplayName("unbounded map values are rejected instead of becoming catch-all additionalProperties")
    void unboundedMapValueFailsFast() {
        assertThatThrownBy(() -> JsonSchemaBuilder.forType(UnsupportedMap.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload.*")
                .hasMessageContaining(Object.class.getName());
    }

    @Test
    @DisplayName("unbounded wildcard collections are rejected instead of becoming catch-all item schemas")
    void unboundedWildcardFailsFast() {
        assertThatThrownBy(() -> JsonSchemaBuilder.forType(UnsupportedWildcard.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items[]")
                .hasMessageContaining(Object.class.getName());
    }

    private static JsonNode json(String source) throws Exception {
        return MAPPER.readTree(source);
    }
}
