package ai.doctruth.internal.citation;

import java.util.Map;

import ai.doctruth.Citation;
import ai.doctruth.ExtractionException;
import ai.doctruth.ParsedDocument;

/**
 * Internal evidence gate for extraction results that promise source-backed provenance.
 *
 * @hidden
 */
public final class EvidenceGate {

    private EvidenceGate() {}

    public static Map<String, Citation> match(
            Object value, ParsedDocument doc, boolean needsCitations, boolean requireEvidence, int retries)
            throws ExtractionException {
        if (!needsCitations) {
            return Map.of();
        }
        Map<String, Citation> citations = new CitationMatcher().matchAll(value, doc);
        if (requireEvidence) {
            requireAvailable(citations, retries);
        }
        return citations;
    }

    private static void requireAvailable(Map<String, Citation> citations, int retries) throws ExtractionException {
        var missing = citations.entrySet().stream()
                .filter(e -> e.getValue().matchScore() == 0.0)
                .findFirst();
        if (missing.isPresent()) {
            var entry = missing.get();
            throw new ExtractionException(
                    "EXTRACTION_EVIDENCE_MISSING",
                    "field " + entry.getKey() + " citation evidence is unavailable",
                    retries);
        }
    }
}
