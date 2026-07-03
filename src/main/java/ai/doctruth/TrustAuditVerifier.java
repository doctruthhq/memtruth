package ai.doctruth;

import java.io.IOException;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies a TrustDocument audit package against the canonical TrustDocument JSON.
 *
 * @since 1.0.0
 */
public final class TrustAuditVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TrustAuditVerifier() {
        throw new AssertionError("no instances");
    }

    public static void verify(TrustDocument document, String auditJson) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(auditJson, "auditJson");
        var expected = read(document.toAuditJson());
        var actual = read(auditJson);
        requireEqual("format", expected, actual);
        requireEqual("docId", expected, actual);
        requireEqual("sourceHash", expected, actual);
        requireEqual("canonicalHash", expected, actual);
        requireEqual("auditGradeStatus", expected, actual);
        requireEqual("evidenceHash", expected, actual);
        requireEqual("parserRun", expected, actual);
        requireEqual("evidence", expected, actual);
    }

    private static void requireEqual(String field, JsonNode expected, JsonNode actual) {
        if (!expected.path(field).equals(actual.path(field))) {
            throw new IllegalArgumentException("audit package " + field + " mismatch");
        }
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid audit JSON", e);
        }
    }
}
