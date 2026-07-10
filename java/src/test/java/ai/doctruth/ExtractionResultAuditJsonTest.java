package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import ai.doctruth.spi.SignatureProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for {@link ExtractionResult#toAuditJson()} / {@link ExtractionResult#toAuditJson(Path)}.
 *
 * <p>The output is W3C PROV-O JSON-LD — the audit format auditors and compliance teams
 * already know how to ingest. The library's brand promise is "every field traceable",
 * and {@code toAuditJson} is the concrete artefact that proves it.
 */
class ExtractionResultAuditJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Person(String name, int age) {}

    private static ExtractionResult<Person> sample(Optional<Instant> sourcePublishedAt) {
        return sample(sourcePublishedAt, Optional.empty(), Optional.empty());
    }

    private static ExtractionResult<Person> sample(
            Optional<Instant> sourcePublishedAt, Optional<String> region, Optional<Instant> retainUntil) {
        var loc = new SourceLocation(1, 1, 3, 3, 0);
        var citation = new Citation(loc, "Alex Chen", 0.97);
        var confidence = new Confidence(0.91, "exact substring match");
        var prov = new Provenance(
                "anthropic",
                "claude-sonnet-4-5-20250929",
                Instant.parse("2026-05-07T07:30:00Z"),
                sourcePublishedAt,
                region,
                retainUntil,
                0);
        return new ExtractionResult<>(
                new Person("Alex Chen", 30), Map.of("name", citation), Map.of("name", confidence), prov);
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("toAuditJson() returns a non-blank JSON-LD string with the @context PROV-O marker")
        void contextHeader() throws Exception {
            var json = sample(Optional.empty()).toAuditJson();

            assertThat(json).isNotBlank();
            JsonNode tree = MAPPER.readTree(json);
            assertThat(tree.path("@context").asText()).isEqualTo("https://www.w3.org/ns/prov");
            assertThat(tree.path("@type").asText()).isEqualTo("prov:Entity");
        }

        @Test
        @DisplayName("the JSON includes the extracted value verbatim under 'doctruth:value'")
        void valueRoundTrips() throws Exception {
            var json = sample(Optional.empty()).toAuditJson();
            JsonNode tree = MAPPER.readTree(json);
            JsonNode value = tree.path("doctruth:value");

            assertThat(value.path("name").asText()).isEqualTo("Alex Chen");
            assertThat(value.path("age").asInt()).isEqualTo(30);
        }

        @Test
        @DisplayName("'prov:wasGeneratedBy' contains the model name, version, and extractedAt timestamp")
        void wasGeneratedByCarriesModelMetadata() throws Exception {
            var json = sample(Optional.empty()).toAuditJson();
            JsonNode tree = MAPPER.readTree(json);
            JsonNode activity = tree.path("prov:wasGeneratedBy");

            assertThat(activity.path("@type").asText()).isEqualTo("prov:Activity");
            assertThat(activity.path("prov:startedAtTime").asText()).isEqualTo("2026-05-07T07:30:00Z");
            JsonNode agent = activity.path("prov:wasAssociatedWith");
            assertThat(agent.path("@type").asText()).isEqualTo("prov:SoftwareAgent");
            assertThat(agent.path("rdfs:label").asText()).isEqualTo("anthropic");
            assertThat(agent.path("prov:version").asText()).isEqualTo("claude-sonnet-4-5-20250929");
        }

        @Test
        @DisplayName("each citation appears as an entry under 'prov:wasDerivedFrom' with location + matchScore")
        void citationsAppearInWasDerivedFrom() throws Exception {
            var json = sample(Optional.empty()).toAuditJson();
            JsonNode tree = MAPPER.readTree(json);
            JsonNode derivations = tree.path("prov:wasDerivedFrom");

            assertThat(derivations.isArray()).isTrue();
            assertThat(derivations).hasSize(1);
            JsonNode entry = derivations.get(0);
            assertThat(entry.path("doctruth:fieldPath").asText()).isEqualTo("name");
            assertThat(entry.path("prov:value").asText()).isEqualTo("Alex Chen");
            assertThat(entry.path("doctruth:matchScore").asDouble()).isEqualTo(0.97);
            JsonNode src = entry.path("doctruth:sourceLocation");
            assertThat(src.path("pageStart").asInt()).isEqualTo(1);
            assertThat(src.path("lineStart").asInt()).isEqualTo(3);
        }

        @Test
        @DisplayName("a citation bounding box is exported when present")
        void citationBoundingBoxAppears() throws Exception {
            var loc = new SourceLocation(1, 1, 3, 3, 0);
            var box = new BoundingBox(10.0, 20.0, 110.0, 40.0);
            var citation = new Citation(loc, "Alex Chen", 0.97, Optional.of(box));
            var confidence = new Confidence(0.91, "exact substring match");
            var prov = new Provenance(
                    "anthropic",
                    "claude-sonnet-4-5-20250929",
                    Instant.parse("2026-05-07T07:30:00Z"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0);
            var result = new ExtractionResult<>(
                    new Person("Alex Chen", 30), Map.of("name", citation), Map.of("name", confidence), prov);

            JsonNode entry = MAPPER.readTree(result.toAuditJson())
                    .path("prov:wasDerivedFrom")
                    .get(0);

            JsonNode bbox = entry.path("doctruth:boundingBox");
            assertThat(bbox.path("x0").asDouble()).isEqualTo(10.0);
            assertThat(bbox.path("y1").asDouble()).isEqualTo(40.0);
        }

        @Test
        @DisplayName("each confidence entry appears under 'doctruth:confidence' with score + rationale")
        void confidenceMapAppears() throws Exception {
            var json = sample(Optional.empty()).toAuditJson();
            JsonNode tree = MAPPER.readTree(json);
            JsonNode confidence = tree.path("doctruth:confidence").path("name");

            assertThat(confidence.path("score").asDouble()).isEqualTo(0.91);
            assertThat(confidence.path("rationale").asText()).isEqualTo("exact substring match");
        }

        @Test
        @DisplayName("retries count is exposed under 'doctruth:retries'")
        void retriesExposed() throws Exception {
            var json = sample(Optional.empty()).toAuditJson();
            JsonNode tree = MAPPER.readTree(json);

            assertThat(tree.path("doctruth:retries").asInt()).isZero();
        }

        @Test
        @DisplayName("a result with sourcePublishedAt set includes 'doctruth:sourcePublishedAt'; "
                + "a result without bi-temporal omits the field entirely")
        void biTemporalConditional() throws Exception {
            var withTimestamp = sample(Optional.of(Instant.parse("2026-01-15T00:00:00Z")));
            var without = sample(Optional.empty());

            JsonNode tWith = MAPPER.readTree(withTimestamp.toAuditJson());
            JsonNode tWithout = MAPPER.readTree(without.toAuditJson());

            assertThat(tWith.path("doctruth:sourcePublishedAt").asText()).isEqualTo("2026-01-15T00:00:00Z");
            assertThat(tWithout.has("doctruth:sourcePublishedAt")).isFalse();
        }

        @Test
        @DisplayName("an extraction with empty citations + empty confidence still produces valid JSON-LD")
        void emptyMapsStillValid() throws Exception {
            var prov = new Provenance(
                    "anthropic",
                    "v-test",
                    Instant.parse("2026-05-07T07:30:00Z"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0);
            var result = new ExtractionResult<>(new Person("X", 0), Map.of(), Map.of(), prov);

            String json = result.toAuditJson();
            JsonNode tree = MAPPER.readTree(json);

            assertThat(tree.path("@context").asText()).isEqualTo("https://www.w3.org/ns/prov");
            assertThat(tree.path("prov:wasDerivedFrom").isArray()).isTrue();
            assertThat(tree.path("prov:wasDerivedFrom")).isEmpty();
            assertThat(tree.path("doctruth:confidence").isObject()).isTrue();
            assertThat(tree.path("doctruth:confidence")).isEmpty();
        }
    }

    @Nested
    @DisplayName("file output")
    class FileOutput {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("toAuditJson(Path) writes the same content as toAuditJson() to the supplied file")
        void writesSameContent(@TempDir Path dir) throws IOException {
            var result = sample(Optional.empty());
            var path = dir.resolve("audit.json");

            result.toAuditJson(path);

            String onDisk = Files.readString(path);
            assertThat(onDisk).isEqualTo(result.toAuditJson());
        }

        @Test
        @DisplayName("toAuditJson(Path) creates parent directories if they do not exist")
        void createsParentDirs(@TempDir Path dir) throws IOException {
            var result = sample(Optional.empty());
            var nested = dir.resolve("a/b/c/audit.json");

            result.toAuditJson(nested);

            assertThat(Files.exists(nested)).isTrue();
            assertThat(Files.size(nested)).isGreaterThan(0);
        }

        @Test
        @DisplayName("toAuditJson(null) throws NullPointerException with 'path' in the message")
        void nullPathRejects() {
            var result = sample(Optional.empty());

            assertThatNullPointerException()
                    .isThrownBy(() -> result.toAuditJson((Path) null))
                    .withMessageContaining("path");
        }
    }

    @Nested
    @DisplayName("residency + retention (Provenance.region + retainUntil)")
    class ResidencyAndRetention {

        @Test
        @DisplayName("region present → JSON includes 'doctruth:region' with the correct value")
        void regionPresentSurfaces() throws Exception {
            var result = sample(Optional.empty(), Optional.of("ap-southeast-2"), Optional.empty());

            JsonNode tree = MAPPER.readTree(result.toAuditJson());

            assertThat(tree.path("doctruth:region").asText()).isEqualTo("ap-southeast-2");
        }

        @Test
        @DisplayName("region absent → JSON does NOT have 'doctruth:region' key")
        void regionAbsentOmitted() throws Exception {
            var result = sample(Optional.empty(), Optional.empty(), Optional.empty());

            JsonNode tree = MAPPER.readTree(result.toAuditJson());

            assertThat(tree.has("doctruth:region")).isFalse();
        }

        @Test
        @DisplayName("retainUntil present → JSON includes 'doctruth:retainUntil' with the ISO-8601 instant")
        void retainUntilPresentSurfaces() throws Exception {
            var horizon = Instant.parse("2033-05-07T00:00:00Z");
            var result = sample(Optional.empty(), Optional.empty(), Optional.of(horizon));

            JsonNode tree = MAPPER.readTree(result.toAuditJson());

            assertThat(tree.path("doctruth:retainUntil").asText()).isEqualTo("2033-05-07T00:00:00Z");
        }

        @Test
        @DisplayName("retainUntil absent → JSON does NOT include 'doctruth:retainUntil' key")
        void retainUntilAbsentOmitted() throws Exception {
            var result = sample(Optional.empty(), Optional.empty(), Optional.empty());

            JsonNode tree = MAPPER.readTree(result.toAuditJson());

            assertThat(tree.has("doctruth:retainUntil")).isFalse();
        }
    }

    @Nested
    @DisplayName("signing (SignatureProvider overload)")
    class Signing {

        @Test
        @DisplayName("toAuditJson(IDENTITY) returns the same string as toAuditJson()")
        void identityIsPassthrough() {
            var result = sample(Optional.empty());

            assertThat(result.toAuditJson(SignatureProvider.IDENTITY)).isEqualTo(result.toAuditJson());
        }

        @Test
        @DisplayName("a custom uppercasing SignatureProvider wraps the output (round-trip shape)")
        void customSignerIsApplied() {
            var result = sample(Optional.empty());
            SignatureProvider upper = String::toUpperCase;

            String wrapped = result.toAuditJson(upper);

            assertThat(wrapped).isEqualTo(result.toAuditJson().toUpperCase());
            // Sanity: the OSS audit JSON contains lowercase '@context' which uppercases.
            assertThat(wrapped).contains("@CONTEXT");
        }

        @Test
        @DisplayName("toAuditJson((SignatureProvider) null) throws NullPointerException with 'signer' in the message")
        void nullSignerRejected() {
            var result = sample(Optional.empty());

            assertThatNullPointerException()
                    .isThrownBy(() -> result.toAuditJson((SignatureProvider) null))
                    .withMessageContaining("signer");
        }

        @Test
        @DisplayName("toAuditJson(path, IDENTITY) writes the same content as toAuditJson(IDENTITY)")
        void pathSigningWritesIdenticalContent(@TempDir Path dir) throws IOException {
            var result = sample(Optional.empty());
            var path = dir.resolve("audit.json");

            result.toAuditJson(path, SignatureProvider.IDENTITY);

            assertThat(Files.readString(path)).isEqualTo(result.toAuditJson(SignatureProvider.IDENTITY));
        }

        @Test
        @DisplayName("toAuditJson(path, null) throws NullPointerException on signer")
        void pathSigningNullSignerRejected(@TempDir Path dir) {
            var result = sample(Optional.empty());
            var path = dir.resolve("audit.json");

            assertThatNullPointerException()
                    .isThrownBy(() -> result.toAuditJson(path, null))
                    .withMessageContaining("signer");
        }

        @Test
        @DisplayName("toAuditJson(null, IDENTITY) throws NullPointerException on path")
        void pathSigningNullPathRejected() {
            var result = sample(Optional.empty());

            assertThatNullPointerException()
                    .isThrownBy(() -> result.toAuditJson(null, SignatureProvider.IDENTITY))
                    .withMessageContaining("path");
        }
    }
}
