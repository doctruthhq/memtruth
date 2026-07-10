package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractionResultConvenienceTest {

    @TempDir
    Path tempDir;

    @Test
    void citationReturnsFieldCitationByPath() {
        var citation = new Citation(new SourceLocation(1, 1, 2, 2, 10), "Alex Chen", 0.99);
        var result = result(Map.of("name", citation));

        assertThat(result.citation("name")).isEqualTo(citation);
        assertThat(result.citation("missing")).isNull();
        assertThat(result.findCitation("name")).contains(citation);
        assertThat(result.findCitation("missing")).isEmpty();
        assertThat(result.requireCitation("name")).isEqualTo(citation);
    }

    @Test
    void citationRejectsNullFieldPath() {
        var result = result(Map.of());

        assertThatNullPointerException().isThrownBy(() -> result.citation(null)).withMessageContaining("fieldPath");
        assertThatNullPointerException()
                .isThrownBy(() -> result.findCitation(null))
                .withMessageContaining("fieldPath");
        assertThatNullPointerException()
                .isThrownBy(() -> result.requireCitation(null))
                .withMessageContaining("fieldPath");
    }

    @Test
    void requireCitationExplainsMissingFieldPath() {
        var result = result(Map.of());

        assertThatThrownBy(() -> result.requireCitation("name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name")
                .hasMessageContaining("No citation");
    }

    @Test
    void writeAuditPathDelegatesToAuditJsonFileOutput() throws Exception {
        var result = result(Map.of("name", new Citation(new SourceLocation(1, 1, 2, 2, 10), "Alex Chen", 0.99)));
        Path path = tempDir.resolve("audit/result.json");

        result.writeAudit(path);

        assertThat(Files.readString(path)).isEqualTo(result.toAuditJson());
    }

    @Test
    void writeAuditStringDelegatesToAuditJsonFileOutput() throws Exception {
        var result = result(Map.of("name", new Citation(new SourceLocation(1, 1, 2, 2, 10), "Alex Chen", 0.99)));
        Path path = tempDir.resolve("audit-string.json");

        result.writeAudit(path.toString());

        assertThat(Files.readString(path)).isEqualTo(result.toAuditJson());
    }

    private static ExtractionResult<Person> result(Map<String, Citation> citations) {
        return new ExtractionResult<>(
                new Person("Alex Chen"),
                citations,
                Map.of(),
                new Provenance(
                        "openai",
                        "test-model",
                        Instant.EPOCH,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        0));
    }

    private record Person(String name) {}
}
