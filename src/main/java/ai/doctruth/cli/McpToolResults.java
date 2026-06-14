package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import ai.doctruth.BoundingBox;
import ai.doctruth.ModelCacheArtifact;
import ai.doctruth.ModelCacheReport;
import ai.doctruth.ModelCacheVerifier;
import ai.doctruth.ModelDescriptor;
import ai.doctruth.ParserWarning;
import ai.doctruth.TrustDocument;
import ai.doctruth.TrustRenderedDocument;
import ai.doctruth.TrustSourceMapEntry;
import ai.doctruth.TrustTable;
import ai.doctruth.TrustUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class McpToolResults {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpToolResults() {
        throw new AssertionError("no instances");
    }

    static ObjectNode parseDocument(TrustDocument doc, String format, boolean sourceMap) throws IOException {
        TrustRenderedDocument rendered = rendered(doc, format, sourceMap);
        ObjectNode structured = baseStructured(doc);
        structured.put("format", rendered.format());
        structured.put("compact", rendered.text());
        structured.set("jsonEvidence", evidenceWithLocations(doc));
        structured.set("sourceMap", sourceMapNode(rendered));
        return result(rendered.text(), structured);
    }

    static ObjectNode layoutRegions(TrustDocument doc) {
        ObjectNode structured = baseStructured(doc);
        ArrayNode regions = MAPPER.createArrayNode();
        doc.body().units().stream()
                .filter(unit -> unit.location().boundingBox().isPresent())
                .forEach(unit -> regions.add(regionNode(unit)));
        structured.put("contentType", "layout_regions");
        structured.set("regions", regions);
        return result(regions.size() + " layout regions", structured);
    }

    static ObjectNode tableCells(TrustDocument doc) {
        ObjectNode structured = baseStructured(doc);
        ArrayNode tables = MAPPER.createArrayNode();
        doc.body().tables().forEach(table -> tables.add(tableNode(table)));
        structured.put("contentType", "table_cells");
        structured.set("tables", tables);
        return result(tables.size() + " tables", structured);
    }

    static ObjectNode evidenceSpan(TrustDocument doc, String evidenceSpanId) {
        TrustUnit unit = unitForEvidenceSpan(doc, evidenceSpanId);
        ObjectNode structured = baseStructured(doc);
        structured.put("contentType", "evidence_span");
        structured.set("span", spanNode(unit, evidenceSpanId));
        return result(unit.content().text(), structured);
    }

    static ObjectNode verifyCitation(TrustDocument doc, String evidenceSpanId, String quote) {
        TrustUnit unit = unitForEvidenceSpan(doc, evidenceSpanId);
        boolean verified = unit.content().text().contains(quote);
        ObjectNode verification = MAPPER.createObjectNode();
        verification.put("evidenceSpanId", evidenceSpanId);
        verification.put("verified", verified);
        verification.put("matchScore", verified ? 1.0 : 0.0);
        verification.put("unitId", unit.unitId());
        ObjectNode structured = baseStructured(doc);
        structured.put("contentType", "verify_citation");
        structured.set("verification", verification);
        return result(verification.toString(), structured);
    }

    static ObjectNode warmModelCache(JsonNode arguments) {
        Path cacheDir = Path.of(requiredText(arguments, "cacheDir"));
        var descriptors = descriptors(arguments.path("models"));
        ModelCacheReport report = ModelCacheVerifier.verify(cacheDir, descriptors);
        ObjectNode structured = MAPPER.createObjectNode();
        structured.put("contentType", "model_cache");
        structured.put("cacheDir", cacheDir.toString());
        structured.put("allReady", report.allReady());
        structured.put("networkAccessRequired", false);
        structured.put("totalSizeBytes", report.totalSizeBytes());
        structured.set("artifacts", artifactsNode(report));
        structured.set("warnings", warningsNode(report.warnings()));
        return result(report.allReady() ? "model cache ready" : "model cache incomplete", structured);
    }

    private static TrustRenderedDocument rendered(TrustDocument doc, String format, boolean sourceMap) {
        return switch (format) {
            case "compact_llm", "compact" -> sourceMap ? doc.toCompactLlmWithSourceMap() : compactOnly(doc);
            case "json_evidence" -> new TrustRenderedDocument(
                    "json_evidence", doc.toJsonEvidence(), doc.source().sourceHash(), doc.canonicalHash(), List.of());
            case "json_full" -> new TrustRenderedDocument(
                    "json_full", doc.toJsonFull(), doc.source().sourceHash(), doc.canonicalHash(), List.of());
            default -> throw new UsageException("unknown MCP parse_document format: " + format);
        };
    }

    private static TrustRenderedDocument compactOnly(TrustDocument doc) {
        return new TrustRenderedDocument(
                "compact_llm", doc.toCompactLlm(), doc.source().sourceHash(), doc.canonicalHash(), List.of());
    }

    private static ObjectNode result(String textContent, ObjectNode structured) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("isError", false);
        ArrayNode content = MAPPER.createArrayNode();
        ObjectNode text = MAPPER.createObjectNode();
        text.put("type", "text");
        text.put("text", textContent);
        content.add(text);
        result.set("content", content);
        result.set("structuredContent", structured);
        return result;
    }

    private static ObjectNode baseStructured(TrustDocument doc) {
        ObjectNode structured = MAPPER.createObjectNode();
        structured.put("docId", doc.docId());
        structured.put("sourceHash", doc.source().sourceHash());
        structured.put("auditGradeStatus", doc.auditGradeStatus().name());
        return structured;
    }

    private static List<ModelDescriptor> descriptors(JsonNode models) {
        if (!models.isArray()) {
            throw new UsageException("MCP argument is required: models");
        }
        return java.util.stream.StreamSupport.stream(models.spliterator(), false)
                .map(model -> new ModelDescriptor(
                        requiredText(model, "name"),
                        requiredText(model, "version"),
                        requiredText(model, "sha256"),
                        model.path("sizeBytes").asLong(0),
                        model.path("required").asBoolean(true)))
                .toList();
    }

    private static ArrayNode artifactsNode(ModelCacheReport report) {
        ArrayNode artifacts = MAPPER.createArrayNode();
        for (ModelCacheArtifact artifact : report.artifacts()) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("name", artifact.descriptor().name());
            item.put("version", artifact.descriptor().version());
            item.put("identity", artifact.descriptor().identity());
            item.put("status", artifact.status().name());
            item.put("actualSizeBytes", artifact.actualSizeBytes());
            item.put("actualSha256", artifact.actualSha256());
            artifacts.add(item);
        }
        return artifacts;
    }

    private static ArrayNode warningsNode(List<ParserWarning> warnings) {
        ArrayNode nodes = MAPPER.createArrayNode();
        for (ParserWarning warning : warnings) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("code", warning.code());
            node.put("severity", warning.severity().name());
            node.put("message", warning.message());
            nodes.add(node);
        }
        return nodes;
    }

    private static ObjectNode regionNode(TrustUnit unit) {
        ObjectNode region = MAPPER.createObjectNode();
        region.put("unitId", unit.unitId());
        region.put("kind", unit.kind().name());
        region.put("page", unit.location().page());
        region.put("readingOrder", unit.location().readingOrder());
        region.put("text", unit.content().text());
        unit.location().boundingBox().ifPresent(box -> region.set("boundingBox", boundingBoxNode(box)));
        ArrayNode spans = MAPPER.createArrayNode();
        unit.evidence().evidenceSpanIds().forEach(spans::add);
        region.set("evidenceSpanIds", spans);
        return region;
    }

    private static ObjectNode tableNode(TrustTable table) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tableId", table.tableId());
        node.put("page", table.pageNumber());
        table.boundingBox().ifPresent(box -> node.set("boundingBox", boundingBoxNode(box)));
        ArrayNode cells = MAPPER.createArrayNode();
        table.cells().forEach(cell -> {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("cellId", cell.cellId());
            item.put("rowStart", cell.rowRange().start());
            item.put("rowEnd", cell.rowRange().end());
            item.put("columnStart", cell.columnRange().start());
            item.put("columnEnd", cell.columnRange().end());
            item.put("text", cell.text());
            cell.boundingBox().ifPresent(box -> item.set("boundingBox", boundingBoxNode(box)));
            cells.add(item);
        });
        node.set("cells", cells);
        return node;
    }

    private static ObjectNode spanNode(TrustUnit unit, String evidenceSpanId) {
        ObjectNode span = regionNode(unit);
        span.put("evidenceSpanId", evidenceSpanId);
        span.put("sourceObjectId", unit.content().sourceObjectId());
        ObjectNode confidence = MAPPER.createObjectNode();
        confidence.put("score", unit.evidence().confidence().score());
        confidence.put("rationale", unit.evidence().confidence().rationale());
        span.set("confidence", confidence);
        return span;
    }

    private static TrustUnit unitForEvidenceSpan(TrustDocument doc, String evidenceSpanId) {
        return doc.body().units().stream()
                .filter(unit -> unit.evidence().evidenceSpanIds().contains(evidenceSpanId))
                .findFirst()
                .orElseThrow(() -> new UsageException("unknown evidence span id: " + evidenceSpanId));
    }

    private static ObjectNode sourceMapNode(TrustRenderedDocument rendered) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("format", rendered.format());
        node.put("text", rendered.text());
        node.put("sourceHash", rendered.sourceHash());
        node.put("contentHash", rendered.contentHash());
        ArrayNode entries = MAPPER.createArrayNode();
        for (TrustSourceMapEntry entry : rendered.sourceMap()) {
            entries.add(sourceMapEntryNode(entry));
        }
        node.set("sourceMap", entries);
        return node;
    }

    private static ObjectNode sourceMapEntryNode(TrustSourceMapEntry entry) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("startOffset", entry.startOffset());
        item.put("endOffset", entry.endOffset());
        item.put("unitId", entry.unitId());
        ArrayNode evidence = MAPPER.createArrayNode();
        entry.evidenceSpanIds().forEach(evidence::add);
        item.set("evidenceSpanIds", evidence);
        return item;
    }

    private static ObjectNode evidenceWithLocations(TrustDocument doc) throws IOException {
        ObjectNode evidence = (ObjectNode) MAPPER.readTree(doc.toJsonEvidence());
        ArrayNode units = (ArrayNode) evidence.path("units");
        for (int i = 0; i < units.size() && i < doc.body().units().size(); i++) {
            TrustUnit unit = doc.body().units().get(i);
            ObjectNode location = MAPPER.createObjectNode();
            location.put("page", unit.location().page());
            location.put("readingOrder", unit.location().readingOrder());
            unit.location().boundingBox().ifPresent(box -> location.set("boundingBox", boundingBoxNode(box)));
            ((ObjectNode) units.get(i)).set("location", location);
        }
        return evidence;
    }

    private static ObjectNode boundingBoxNode(BoundingBox box) {
        ObjectNode bbox = MAPPER.createObjectNode();
        bbox.put("x0", box.x0());
        bbox.put("y0", box.y0());
        bbox.put("x1", box.x1());
        bbox.put("y1", box.y1());
        return bbox;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new UsageException("MCP argument is required: " + field);
        }
        return value;
    }
}
