package ai.doctruth.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class IngestAuditJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IngestAuditJson() {
        throw new AssertionError("no instances");
    }

    static String toJson(IngestAuditReport report) throws CliException {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toNode(report));
        } catch (JsonProcessingException e) {
            throw new CliException("failed to serialize ingest audit", e);
        }
    }

    private static ObjectNode toNode(IngestAuditReport report) {
        var root = MAPPER.createObjectNode();
        root.put("root", report.root().toString());
        root.put("totalFiles", report.totalFiles());
        root.put("parsed", report.parsed());
        root.put("failed", report.failed());
        root.set("issueSummary", MAPPER.valueToTree(report.issueSummary()));
        var files = MAPPER.createArrayNode();
        report.files().forEach(file -> files.add(fileNode(file)));
        root.set("files", files);
        return root;
    }

    private static ObjectNode fileNode(IngestAuditFileResult file) {
        var node = MAPPER.createObjectNode();
        node.put("filename", file.filename());
        node.put("status", file.status());
        node.put("errorCode", file.errorCode());
        node.put("pages", file.pages());
        node.put("sections", file.sections());
        node.put("textSections", file.textSections());
        node.put("textChars", file.textChars());
        node.put("textWithBbox", file.textWithBbox());
        node.put("maxBlockChars", file.maxBlockChars());
        node.put("maxBlockLines", file.maxBlockLines());
        node.set("kindCounts", MAPPER.valueToTree(file.kindCounts()));
        node.set("findings", findings(file));
        return node;
    }

    private static ArrayNode findings(IngestAuditFileResult file) {
        var out = MAPPER.createArrayNode();
        file.findings().forEach(finding -> {
            var node = MAPPER.createObjectNode();
            node.put("category", finding.category());
            node.put("reason", finding.reason());
            node.put("value", finding.value());
            node.put("threshold", finding.threshold());
            out.add(node);
        });
        return out;
    }
}
