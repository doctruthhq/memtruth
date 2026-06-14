package ai.doctruth;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class TrustDocumentJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TrustDocumentJson() {
        throw new AssertionError("no instances");
    }

    static TrustDocument fromJsonFull(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            var document = new TrustDocument(
                    text(root, "docId"),
                    source(root.path("source")),
                    body(root.path("body")),
                    parserRun(root.path("parserRun")),
                    AuditGradeStatus.valueOf(text(root, "auditGradeStatus")));
            TrustDocumentLayeredOutputs.attach(document, root.path("contentBlocks"), root.path("parseTrace"));
            return document;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid TrustDocument JSON", e);
        }
    }

    private static TrustDocumentSource source(JsonNode node) {
        var metadata = metadata(node.path("metadata"));
        return new TrustDocumentSource(text(node, "sourceFilename"), text(node, "sourceHash"), metadata);
    }

    private static DocumentMetadata metadata(JsonNode node) {
        Optional<Instant> publishedAt = node.hasNonNull("sourcePublishedAt")
                ? Optional.of(Instant.parse(node.path("sourcePublishedAt").asText()))
                : Optional.empty();
        return new DocumentMetadata(text(node, "sourceFilename"), integer(node, "pageCount"), publishedAt);
    }

    private static TrustDocumentBody body(JsonNode node) {
        return new TrustDocumentBody(pages(node.path("pages")), units(node.path("units")), tables(node.path("tables")));
    }

    private static List<TrustPage> pages(JsonNode nodes) {
        var pages = new ArrayList<TrustPage>();
        nodes.forEach(node -> pages.add(new TrustPage(
                integer(node, "pageNumber"),
                integer(node, "width"),
                integer(node, "height"),
                node.path("textLayerAvailable").asBoolean(),
                node.path("imageHash").asText())));
        return List.copyOf(pages);
    }

    private static List<TrustUnit> units(JsonNode nodes) {
        var units = new ArrayList<TrustUnit>();
        nodes.forEach(node -> units.add(new TrustUnit(
                text(node, "unitId"),
                TrustUnitKind.valueOf(text(node, "kind")),
                unitLocation(node.path("location")),
                new TrustUnitContent(text(node, "text"), text(node, "sourceObjectId")),
                new TrustUnitEvidence(strings(node.path("evidenceSpanIds")), confidence(node.path("confidence")), warnings(node.path("warnings"))))));
        return List.copyOf(units);
    }

    private static TrustUnitLocation unitLocation(JsonNode node) {
        return new TrustUnitLocation(integer(node, "page"), bbox(node.path("boundingBox")), integer(node, "readingOrder"));
    }

    private static List<TrustTable> tables(JsonNode nodes) {
        var tables = new ArrayList<TrustTable>();
        nodes.forEach(node -> tables.add(new TrustTable(
                text(node, "tableId"),
                integer(node, "pageNumber"),
                bbox(node.path("boundingBox")),
                confidence(node.path("confidence")),
                cells(node.path("cells")))));
        return List.copyOf(tables);
    }

    private static List<TrustTableCell> cells(JsonNode nodes) {
        var cells = new ArrayList<TrustTableCell>();
        nodes.forEach(node -> cells.add(new TrustTableCell(
                text(node, "cellId"),
                range(node.path("rowRange")),
                range(node.path("columnRange")),
                bbox(node.path("boundingBox")),
                text(node, "text"))));
        return List.copyOf(cells);
    }

    private static TrustCellRange range(JsonNode node) {
        return new TrustCellRange(integer(node, "start"), integer(node, "end"));
    }

    private static ParserRun parserRun(JsonNode node) {
        return new ParserRun(
                optionalText(node, "parserRunId", "parser-run-0001"),
                text(node, "parserVersion"),
                text(node, "preset"),
                text(node, "backend"),
                strings(node.path("models")),
                warnings(node.path("warnings")));
    }

    private static List<ParserWarning> warnings(JsonNode nodes) {
        var warnings = new ArrayList<ParserWarning>();
        nodes.forEach(node -> warnings.add(new ParserWarning(
                text(node, "code"),
                ParserWarningSeverity.valueOf(text(node, "severity")),
                text(node, "message"))));
        return List.copyOf(warnings);
    }

    private static Confidence confidence(JsonNode node) {
        return new Confidence(node.path("score").asDouble(), text(node, "rationale"));
    }

    private static Optional<BoundingBox> bbox(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(new BoundingBox(
                node.path("x0").asDouble(),
                node.path("y0").asDouble(),
                node.path("x1").asDouble(),
                node.path("y1").asDouble()));
    }

    private static List<String> strings(JsonNode nodes) {
        var values = new ArrayList<String>();
        nodes.forEach(node -> values.add(node.asText()));
        return List.copyOf(values);
    }

    private static int integer(JsonNode node, String field) {
        return node.path(field).asInt();
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing or blank field: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText();
        return value.isBlank() ? fallback : value;
    }
}
