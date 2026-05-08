package ai.doctruth.internal.audit;

import java.util.Map;

import ai.doctruth.Citation;
import ai.doctruth.Confidence;
import ai.doctruth.ExtractionResult;
import ai.doctruth.Provenance;
import ai.doctruth.SourceLocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Render an {@link ExtractionResult} as W3C PROV-O JSON-LD. Single source of audit-format
 * truth for the library — bumping the export format (e.g. switching to a different ontology
 * vocabulary) is one file and one ADR.
 *
 * <p>Output shape:
 *
 * <pre>{@code
 * {
 *   "@context": "https://www.w3.org/ns/prov",
 *   "@type": "prov:Entity",
 *   "doctruth:value": { ... },
 *   "doctruth:retries": 0,
 *   "doctruth:sourcePublishedAt": "2026-01-15T00:00:00Z",   // present iff bi-temporal set
 *   "prov:wasGeneratedBy": {
 *     "@type": "prov:Activity",
 *     "prov:startedAtTime": "2026-05-07T07:30:00Z",
 *     "prov:wasAssociatedWith": {
 *       "@type": "prov:SoftwareAgent",
 *       "rdfs:label": "anthropic",
 *       "prov:version": "claude-sonnet-4-5-20250929"
 *     }
 *   },
 *   "prov:wasDerivedFrom": [
 *     {
 *       "doctruth:fieldPath": "name",
 *       "prov:value": "Alex Chen",
 *       "doctruth:matchScore": 0.97,
 *       "doctruth:sourceLocation": {"pageStart": 1, "pageEnd": 1, "lineStart": 3, "lineEnd": 3, "charOffset": 0}
 *     }, ...
 *   ],
 *   "doctruth:confidence": {
 *     "name": {"score": 0.91, "rationale": "exact substring match"}, ...
 *   }
 * }
 * }</pre>
 */
public final class ProvOExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private ProvOExporter() {
        throw new AssertionError("no instances");
    }

    public static String toJson(ExtractionResult<?> result) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("@context", "https://www.w3.org/ns/prov");
        root.put("@type", "prov:Entity");

        root.set("doctruth:value", MAPPER.valueToTree(result.value()));
        root.put("doctruth:retries", result.provenance().retries());
        result.provenance().sourcePublishedAt().ifPresent(t -> root.put("doctruth:sourcePublishedAt", t.toString()));
        result.provenance().region().ifPresent(r -> root.put("doctruth:region", r));
        result.provenance().retainUntil().ifPresent(t -> root.put("doctruth:retainUntil", t.toString()));

        root.set("prov:wasGeneratedBy", activity(result.provenance()));
        root.set("prov:wasDerivedFrom", derivations(result.citations()));
        root.set("doctruth:confidence", confidenceMap(result.confidence()));

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("PROV-O serialisation failed; this is a bug", e);
        }
    }

    private static ObjectNode activity(Provenance prov) {
        ObjectNode activity = MAPPER.createObjectNode();
        activity.put("@type", "prov:Activity");
        activity.put("prov:startedAtTime", prov.extractedAt().toString());

        ObjectNode agent = MAPPER.createObjectNode();
        agent.put("@type", "prov:SoftwareAgent");
        agent.put("rdfs:label", prov.model());
        agent.put("prov:version", prov.modelVersion());
        activity.set("prov:wasAssociatedWith", agent);

        return activity;
    }

    private static ArrayNode derivations(Map<String, Citation> citations) {
        ArrayNode array = MAPPER.createArrayNode();
        citations.forEach((path, citation) -> array.add(derivationEntry(path, citation)));
        return array;
    }

    private static ObjectNode derivationEntry(String path, Citation citation) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("@type", "prov:Entity");
        entry.put("doctruth:fieldPath", path);
        entry.put("prov:value", citation.exactQuote());
        entry.put("doctruth:matchScore", citation.matchScore());
        entry.set("doctruth:sourceLocation", locationNode(citation.location()));
        return entry;
    }

    private static ObjectNode locationNode(SourceLocation loc) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("pageStart", loc.pageStart());
        node.put("pageEnd", loc.pageEnd());
        node.put("lineStart", loc.lineStart());
        node.put("lineEnd", loc.lineEnd());
        node.put("charOffset", loc.charOffset());
        return node;
    }

    private static ObjectNode confidenceMap(Map<String, Confidence> confidence) {
        ObjectNode node = MAPPER.createObjectNode();
        confidence.forEach((path, c) -> {
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("score", c.score());
            entry.put("rationale", c.rationale());
            node.set(path, entry);
        });
        return node;
    }
}
