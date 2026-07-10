package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ErrorSemanticsDocumentationTest {

    @Test
    void integrationErrorSemanticsDocumentCoversStableCodes() throws Exception {
        String doc = Files.readString(Path.of("docs/error-handling.md"));

        assertThat(doc)
                .contains("EXTRACTION_SCHEMA_VALIDATION_FAILED")
                .contains("EXTRACTION_EVIDENCE_MISSING")
                .contains("EXTRACTION_PARSE_FAILED")
                .contains("EXTRACTION_PROVIDER_FAILED")
                .contains("PROVIDER_RESPONSE_INVALID")
                .contains("PROVIDER_HTTP_401")
                .contains("PDF_PARSE_FAILED")
                .contains("schema compatibility check failed");
    }
}
