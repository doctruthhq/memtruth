package ai.doctruth;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

final class TrustDocumentRenderers {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final int STREAM_WRITE_CHARS = 256;

    private TrustDocumentRenderers() {
        throw new AssertionError("no instances");
    }

    static String toJsonFull(TrustDocument doc) {
        return compact(jsonFullNode(doc));
    }

    static void writeJsonFull(TrustDocument doc, Writer writer) throws IOException {
        writeJson(writer, jsonFullNode(doc));
    }

    private static ObjectNode jsonFullNode(TrustDocument doc) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("docId", doc.docId());
        root.set("source", sourceNode(doc.source()));
        root.set("body", bodyNode(doc.body()));
        root.set("parserRun", parserRunNode(doc.parserRun()));
        root.put("auditGradeStatus", doc.auditGradeStatus().name());
        return root;
    }

    static String toJsonEvidence(TrustDocument doc) {
        return compact(jsonEvidenceNode(doc));
    }

    static void writeJsonEvidence(TrustDocument doc, Writer writer) throws IOException {
        writeJson(writer, jsonEvidenceNode(doc));
    }

    private static ObjectNode jsonEvidenceNode(TrustDocument doc) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("docId", doc.docId());
        root.put("sourceHash", doc.source().sourceHash());
        root.put("auditGradeStatus", doc.auditGradeStatus().name());
        ArrayNode units = MAPPER.createArrayNode();
        doc.body().units().forEach(unit -> units.add(evidenceUnit(unit)));
        root.set("units", units);
        return root;
    }

    private static ObjectNode contentBlocksRoot(TrustDocument doc) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", "doctruth.content_blocks.v1");
        root.put("docId", doc.docId());
        root.put("sourceHash", doc.source().sourceHash());
        root.set("contentBlocks", TrustDocumentLayeredOutputs.contentBlocks(doc).orElseGet(() -> contentBlocks(doc)));
        return root;
    }

    private static ObjectNode parseTraceRoot(TrustDocument doc) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", "doctruth.parse_trace.v1");
        root.put("docId", doc.docId());
        root.put("sourceHash", doc.source().sourceHash());
        root.set("parseTrace", TrustDocumentLayeredOutputs.parseTrace(doc).orElseGet(() -> parseTrace(doc)));
        return root;
    }

    private static ArrayNode contentBlocks(TrustDocument doc) {
        ArrayNode blocks = MAPPER.createArrayNode();
        sortedUnits(doc).forEach(unit -> blocks.add(contentBlock(unit)));
        return blocks;
    }

    private static ObjectNode contentBlock(TrustUnit unit) {
        int readingOrder = unit.location().readingOrder();
        ObjectNode block = MAPPER.createObjectNode();
        block.put("blockId", id("block", readingOrder));
        block.put("type", blockType(unit));
        block.put("page", unit.location().page());
        unit.location().boundingBox().ifPresent(box -> block.set("bbox", bboxNode(box)));
        block.put("readingOrder", readingOrder);
        block.put("text", unit.content().text());
        block.set("sourceUnitIds", stringArray(unit.unitId()));
        block.set("evidenceSpanIds", MAPPER.valueToTree(unit.evidence().evidenceSpanIds()));
        block.set("warnings", MAPPER.valueToTree(unit.evidence().warnings()));
        return block;
    }

    private static ObjectNode parseTrace(TrustDocument doc) {
        ObjectNode trace = MAPPER.createObjectNode();
        trace.put("traceId", "trace-0001");
        trace.put("parserRunId", doc.parserRun().parserRunId());
        ArrayNode pages = MAPPER.createArrayNode();
        doc.body().pages().forEach(page -> pages.add(tracePage(page, doc)));
        trace.set("pages", pages);
        trace.set("warnings", MAPPER.valueToTree(doc.parserRun().warnings()));
        return trace;
    }

    private static ObjectNode tracePage(TrustPage page, TrustDocument doc) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("pageIndex", page.pageNumber() - 1);
        node.put("pageNumber", page.pageNumber());
        node.set("pageSize", pageSizeNode(page));
        node.set("preprocBlocks", MAPPER.createArrayNode());
        ArrayNode readingBlocks = MAPPER.createArrayNode();
        sortedUnits(doc).stream()
                .filter(unit -> unit.location().page() == page.pageNumber())
                .forEach(unit -> readingBlocks.add(traceBlock(unit)));
        node.set("readingBlocks", readingBlocks);
        node.set("discardedBlocks", discardedBlocks(page, doc));
        node.set("images", MAPPER.createArrayNode());
        node.set("tables", MAPPER.createArrayNode());
        node.set("equations", MAPPER.createArrayNode());
        return node;
    }

    private static ArrayNode discardedBlocks(TrustPage page, TrustDocument doc) {
        ArrayNode blocks = MAPPER.createArrayNode();
        TrustDocumentDiscardedBlocks.forDocument(doc).stream()
                .flatMap(List::stream)
                .filter(block -> block.page() == page.pageNumber())
                .forEach(block -> blocks.add(discardedBlock(block)));
        return blocks;
    }

    private static ObjectNode discardedBlock(DiscardedBlock block) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "discarded");
        node.put("reason", block.reason());
        node.put("page", block.page());
        node.put("text", block.text());
        block.boundingBox().ifPresent(box -> node.set("bbox", bboxNode(box)));
        return node;
    }

    private static ObjectNode traceBlock(TrustUnit unit) {
        int readingOrder = unit.location().readingOrder();
        ObjectNode block = MAPPER.createObjectNode();
        block.put("blockId", id("block", readingOrder));
        block.put("type", blockType(unit));
        unit.location().boundingBox().ifPresent(box -> block.set("bbox", bboxNode(box)));
        block.put("readingOrder", readingOrder);
        block.put("confidence", unit.evidence().confidence().score());
        block.put("modelRunId", "");
        block.set("sourceUnitIds", stringArray(unit.unitId()));
        block.set("evidenceSpanIds", MAPPER.valueToTree(unit.evidence().evidenceSpanIds()));
        block.set("warnings", MAPPER.valueToTree(unit.evidence().warnings()));
        block.set("lines", traceLines(unit));
        return block;
    }

    private static ArrayNode traceLines(TrustUnit unit) {
        int readingOrder = unit.location().readingOrder();
        ObjectNode line = MAPPER.createObjectNode();
        line.put("lineId", id("line", readingOrder));
        unit.location().boundingBox().ifPresent(box -> line.set("bbox", bboxNode(box)));
        line.put("text", unit.content().text());
        line.set("spans", traceSpans(unit));
        ArrayNode lines = MAPPER.createArrayNode();
        lines.add(line);
        return lines;
    }

    private static ArrayNode traceSpans(TrustUnit unit) {
        int readingOrder = unit.location().readingOrder();
        String evidenceSpanId = unit.evidence().evidenceSpanIds().isEmpty()
                ? ""
                : unit.evidence().evidenceSpanIds().getFirst();
        ObjectNode span = MAPPER.createObjectNode();
        span.put("spanId", id("trace-span", readingOrder));
        span.put("type", "text");
        span.put("content", unit.content().text());
        unit.location().boundingBox().ifPresent(box -> span.set("bbox", bboxNode(box)));
        span.put("score", unit.evidence().confidence().score());
        span.put("sourceObjectId", unit.content().sourceObjectId());
        span.put("evidenceSpanId", evidenceSpanId);
        ArrayNode spans = MAPPER.createArrayNode();
        spans.add(span);
        return spans;
    }

    static String toMarkdownClean(TrustDocument doc) {
        var out = new StringBuilder();
        appendCleanBlocksInReadingOrder(
                doc, out, TrustDocumentRenderers::tableMarkdown, TrustDocumentRenderers::markdownUnit);
        return out.toString().stripTrailing() + "\n";
    }

    static void writeMarkdownClean(TrustDocument doc, Writer writer) throws IOException {
        boolean[] wrote = new boolean[] {false};
        writeCleanBlocksInReadingOrder(
                doc, writer, wrote, TrustDocumentRenderers::tableMarkdown, TrustDocumentRenderers::markdownUnit);
        writeChunked(writer, "\n");
    }

    static String toMarkdownAnchored(TrustDocument doc) {
        var out = new StringBuilder();
        sortedUnits(doc).forEach(unit -> appendBlock(out, anchoredMarkdown(unit)));
        return out.toString().stripTrailing() + "\n";
    }

    static void writeMarkdownAnchored(TrustDocument doc, Writer writer) throws IOException {
        boolean[] wrote = new boolean[] {false};
        for (var unit : sortedUnits(doc)) {
            writeBlock(writer, wrote, anchoredMarkdown(unit));
        }
        writeChunked(writer, "\n");
    }

    static String toMarkdownReview(TrustDocument doc) {
        var out = new StringBuilder();
        out.append("<!-- doctruth doc=")
                .append(doc.docId())
                .append(" source_hash=")
                .append(doc.source().sourceHash())
                .append(" audit_grade=")
                .append(doc.auditGradeStatus())
                .append(" -->\n\n");
        int currentPage = -1;
        for (var unit : sortedUnits(doc)) {
            if (unit.location().page() != currentPage) {
                currentPage = unit.location().page();
                appendBlock(out, "<!-- page:" + currentPage + " -->");
            }
            appendBlock(out, anchoredMarkdown(unit));
        }
        appendWarnings(out, doc.parserRun().warnings());
        appendUnitWarnings(out, doc);
        return out.toString().stripTrailing() + "\n";
    }

    static void writeMarkdownReview(TrustDocument doc, Writer writer) throws IOException {
        writeChunked(
                writer,
                "<!-- doctruth doc="
                        + doc.docId()
                        + " source_hash="
                        + doc.source().sourceHash()
                        + " audit_grade="
                        + doc.auditGradeStatus()
                        + " -->\n\n");
        boolean[] wrote = new boolean[] {true};
        int currentPage = -1;
        for (var unit : sortedUnits(doc)) {
            if (unit.location().page() != currentPage) {
                currentPage = unit.location().page();
                writeBlock(writer, wrote, "<!-- page:" + currentPage + " -->");
            }
            writeBlock(writer, wrote, anchoredMarkdown(unit));
        }
        writeWarnings(writer, wrote, doc.parserRun().warnings());
        writeUnitWarnings(writer, wrote, doc);
        writeChunked(writer, "\n");
    }

    static String toPlainText(TrustDocument doc) {
        var out = new StringBuilder();
        appendCleanBlocksInReadingOrder(doc, out, TrustDocumentRenderers::tablePlainText, unit -> unit.content()
                .text());
        return out.toString().stripTrailing() + "\n";
    }

    static void writePlainText(TrustDocument doc, Writer writer) throws IOException {
        boolean[] wrote = new boolean[] {false};
        writeCleanBlocksInReadingOrder(
                doc, writer, wrote, TrustDocumentRenderers::tablePlainText, unit -> unit.content()
                        .text());
        writeChunked(writer, "\n");
    }

    static String toCompactLlm(TrustDocument doc) {
        var out = new StringBuilder();
        out.append("doc|")
                .append(escape(doc.docId()))
                .append('|')
                .append(escape(doc.source().sourceHash()))
                .append('\n');
        doc.body().units().stream()
                .sorted(Comparator.comparingInt(unit -> unit.location().readingOrder()))
                .forEach(unit -> appendCompactUnit(out, unit));
        doc.body().tables().forEach(table -> appendCompactTable(out, table));
        doc.parserRun().warnings().forEach(warning -> appendCompactWarning(out, "parser", warning));
        doc.body().units().stream()
                .filter(unit -> !unit.evidence().warnings().isEmpty())
                .sorted(Comparator.comparingInt(unit -> unit.location().readingOrder()))
                .forEach(unit -> unit.evidence()
                        .warnings()
                        .forEach(warning -> appendCompactWarning(out, unit.unitId(), warning)));
        return out.toString().stripTrailing() + "\n";
    }

    static void writeCompactLlm(TrustDocument doc, Writer writer) throws IOException {
        writeChunked(
                writer, "doc|" + escape(doc.docId()) + "|" + escape(doc.source().sourceHash()) + "\n");
        for (var unit : sortedUnits(doc)) {
            writeChunked(writer, compactUnit(unit) + "\n");
        }
        for (var table : doc.body().tables()) {
            writeChunked(writer, compactTable(table) + "\n");
        }
        for (var warning : doc.parserRun().warnings()) {
            writeChunked(writer, compactWarning("parser", warning) + "\n");
        }
        for (var unit : sortedUnits(doc)) {
            for (var warning : unit.evidence().warnings()) {
                writeChunked(writer, compactWarning(unit.unitId(), warning) + "\n");
            }
        }
    }

    static TrustRenderedDocument toMarkdownWithSourceMap(TrustDocument doc) {
        var rendered = renderMarkdownSourceMap(doc);
        return new TrustRenderedDocument(
                rendered.format(),
                rendered.text(),
                rendered.sourceHash(),
                rendered.contentHash(),
                rendered.sourceMap());
    }

    static void writeMarkdownSourceMap(TrustDocument doc, Writer writer) throws IOException {
        writeSourceMapJson(writer, renderMarkdownSourceMap(doc));
    }

    private static RenderedSourceMap renderMarkdownSourceMap(TrustDocument doc) {
        var out = new StringBuilder();
        var sourceMap = new ArrayList<TrustSourceMapEntry>();
        doc.body().units().stream()
                .filter(unit -> unit.kind() != TrustUnitKind.TABLE_CELL)
                .sorted(Comparator.comparingInt(unit -> unit.location().readingOrder()))
                .forEach(unit -> appendMappedBlock(out, sourceMap, unit));
        doc.body().tables().forEach(table -> appendMappedTable(out, sourceMap, doc, table));
        String text = out.toString().stripTrailing() + "\n";
        return new RenderedSourceMap("markdown", text, doc.source().sourceHash(), sha256(text), sourceMap);
    }

    static TrustRenderedDocument toCompactLlmWithSourceMap(TrustDocument doc) {
        var rendered = renderCompactLlmSourceMap(doc);
        return new TrustRenderedDocument(
                rendered.format(),
                rendered.text(),
                rendered.sourceHash(),
                rendered.contentHash(),
                rendered.sourceMap());
    }

    static void writeCompactLlmSourceMap(TrustDocument doc, Writer writer) throws IOException {
        writeSourceMapJson(writer, renderCompactLlmSourceMap(doc));
    }

    private static RenderedSourceMap renderCompactLlmSourceMap(TrustDocument doc) {
        var out = new StringBuilder();
        var sourceMap = new ArrayList<TrustSourceMapEntry>();
        out.append("doc|")
                .append(escape(doc.docId()))
                .append('|')
                .append(escape(doc.source().sourceHash()))
                .append('\n');
        for (var unit : sortedUnits(doc)) {
            appendMappedCompactUnit(out, sourceMap, unit);
        }
        doc.body().tables().forEach(table -> appendCompactTable(out, table));
        doc.parserRun().warnings().forEach(warning -> appendCompactWarning(out, "parser", warning));
        sortedUnits(doc).stream()
                .filter(unit -> !unit.evidence().warnings().isEmpty())
                .forEach(unit -> unit.evidence()
                        .warnings()
                        .forEach(warning -> appendCompactWarning(out, unit.unitId(), warning)));
        String text = out.toString().stripTrailing() + "\n";
        return new RenderedSourceMap("compact_llm", text, doc.source().sourceHash(), sha256(text), sourceMap);
    }

    static String toJsonLines(TrustDocument doc) {
        var out = new StringBuilder();
        ObjectNode document = MAPPER.createObjectNode();
        document.put("type", "document");
        document.put("doc_id", doc.docId());
        document.put("source_hash", doc.source().sourceHash());
        appendJsonLine(out, document);
        sortedUnits(doc).forEach(unit -> {
            ObjectNode node = jsonLineUnit(unit);
            node.put("type", "unit");
            appendJsonLine(out, node);
        });
        doc.body().tables().forEach(table -> {
            ObjectNode node = tableNode(table);
            node.put("type", "table");
            appendJsonLine(out, node);
        });
        return out.toString();
    }

    static void writeJsonLines(TrustDocument doc, Writer writer) throws IOException {
        ObjectNode document = MAPPER.createObjectNode();
        document.put("type", "document");
        document.put("doc_id", doc.docId());
        document.put("source_hash", doc.source().sourceHash());
        writeJsonLine(writer, document);
        for (var unit : sortedUnits(doc)) {
            ObjectNode node = jsonLineUnit(unit);
            node.put("type", "unit");
            writeJsonLine(writer, node);
        }
        for (var table : doc.body().tables()) {
            ObjectNode node = tableNode(table);
            node.put("type", "table");
            writeJsonLine(writer, node);
        }
    }

    static void writeContentBlocks(TrustDocument doc, Writer writer) throws IOException {
        writeJson(writer, contentBlocksRoot(doc));
    }

    static void writeParseTrace(TrustDocument doc, Writer writer) throws IOException {
        writeJson(writer, parseTraceRoot(doc));
    }

    static String toAuditJson(TrustDocument doc) {
        return compact(auditNode(doc));
    }

    static void writeAuditJson(TrustDocument doc, Writer writer) throws IOException {
        writeJson(writer, auditNode(doc));
    }

    static String canonicalHash(TrustDocument doc) {
        return sha256(writer -> writeCanonicalHashInput(doc, writer));
    }

    static void writeCanonicalHashInput(TrustDocument doc, Writer writer) throws IOException {
        writeJsonFull(doc, writer);
    }

    static void writeEvidenceHashInput(TrustDocument doc, Writer writer) throws IOException {
        writeJson(writer, evidenceArray(doc));
    }

    private static ObjectNode auditNode(TrustDocument doc) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", "doctruth.trust_document.audit.v1");
        root.put("docId", doc.docId());
        root.put("sourceHash", doc.source().sourceHash());
        root.put("canonicalHash", doc.canonicalHash());
        root.put("auditGradeStatus", doc.auditGradeStatus().name());
        root.set("parserRun", parserRunNode(doc.parserRun()));
        ArrayNode evidence = evidenceArray(doc);
        root.put("evidenceHash", sha256(writer -> writeEvidenceHashInput(doc, writer)));
        root.set("evidence", evidence);
        return root;
    }

    private static ArrayNode evidenceArray(TrustDocument doc) {
        ArrayNode evidence = MAPPER.createArrayNode();
        sortedUnits(doc).forEach(unit -> evidence.add(evidenceUnit(unit)));
        return evidence;
    }

    static String toHtmlReview(TrustDocument doc) {
        var out = new StringBuilder();
        out.append("<article data-trust-doc-id=\"").append(html(doc.docId())).append("\">\n");
        for (var page : doc.body().pages()) {
            appendHtmlPageStart(out, page);
            sortedUnits(doc).stream()
                    .filter(unit -> unit.location().page() == page.pageNumber())
                    .forEach(unit -> appendHtmlUnit(out, unit));
            doc.body().tables().stream()
                    .filter(table -> table.pageNumber() == page.pageNumber())
                    .forEach(table -> appendHtmlTable(out, doc, table));
            appendHtmlOverlayLayer(out, doc, page);
            out.append("  </section>\n");
        }
        out.append("</article>\n");
        return out.toString();
    }

    static void writeHtmlReview(TrustDocument doc, Writer writer) throws IOException {
        writeChunked(writer, "<article data-trust-doc-id=\"" + html(doc.docId()) + "\">\n");
        for (var page : doc.body().pages()) {
            writeFragment(writer, out -> appendHtmlPageStart(out, page));
            for (var unit : sortedUnits(doc)) {
                if (unit.location().page() == page.pageNumber()) {
                    writeFragment(writer, out -> appendHtmlUnit(out, unit));
                }
            }
            for (var table : doc.body().tables()) {
                if (table.pageNumber() == page.pageNumber()) {
                    writeFragment(writer, out -> appendHtmlTable(out, doc, table));
                }
            }
            writeFragment(writer, out -> appendHtmlOverlayLayer(out, doc, page));
            writeChunked(writer, "  </section>\n");
        }
        writeChunked(writer, "</article>\n");
    }

    static List<TrustDocumentChunk> toChunks(TrustDocument doc, int maxChars) {
        var chunks = new ArrayList<TrustDocumentChunk>();
        var text = new StringBuilder();
        var unitIds = new ArrayList<String>();
        var evidenceIds = new ArrayList<String>();
        for (var unit : sortedUnits(doc)) {
            String rendered = unit.content().text().strip();
            if (rendered.isBlank()) {
                continue;
            }
            int nextLength = text.isEmpty() ? rendered.length() : text.length() + 2 + rendered.length();
            if (!text.isEmpty() && nextLength > maxChars) {
                chunks.add(chunk(chunks.size() + 1, text, unitIds, evidenceIds));
                text.setLength(0);
                unitIds.clear();
                evidenceIds.clear();
            }
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(rendered);
            unitIds.add(unit.unitId());
            evidenceIds.addAll(unit.evidence().evidenceSpanIds());
        }
        if (!text.isEmpty()) {
            chunks.add(chunk(chunks.size() + 1, text, unitIds, evidenceIds));
        }
        return List.copyOf(chunks);
    }

    private static ObjectNode evidenceUnit(TrustUnit unit) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("unitId", unit.unitId());
        node.put("kind", unit.kind().name());
        node.put("page", unit.location().page());
        node.put("text", unit.content().text());
        node.set("evidenceSpanIds", MAPPER.valueToTree(unit.evidence().evidenceSpanIds()));
        return node;
    }

    private static ObjectNode jsonLineUnit(TrustUnit unit) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("unit_id", unit.unitId());
        node.put("kind", unit.kind().name());
        node.put("page", unit.location().page());
        node.put("text", unit.content().text());
        node.set("evidence_span_ids", MAPPER.valueToTree(unit.evidence().evidenceSpanIds()));
        return node;
    }

    private static ObjectNode sourceNode(TrustDocumentSource source) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("sourceFilename", source.sourceFilename());
        node.put("sourceHash", source.sourceHash());
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("sourceFilename", source.metadata().sourceFilename());
        metadata.put("pageCount", source.metadata().pageCount());
        source.metadata().sourcePublishedAt().ifPresent(t -> metadata.put("sourcePublishedAt", t.toString()));
        node.set("metadata", metadata);
        return node;
    }

    private static ObjectNode bodyNode(TrustDocumentBody body) {
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode pages = MAPPER.createArrayNode();
        body.pages().forEach(page -> pages.add(pageNode(page)));
        ArrayNode units = MAPPER.createArrayNode();
        body.units().forEach(unit -> units.add(unitNode(unit)));
        ArrayNode tables = MAPPER.createArrayNode();
        body.tables().forEach(table -> tables.add(tableNode(table)));
        node.set("pages", pages);
        node.set("units", units);
        node.set("tables", tables);
        return node;
    }

    private static ObjectNode parserRunNode(ParserRun parserRun) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("parserRunId", parserRun.parserRunId());
        node.put("parserVersion", parserRun.parserVersion());
        node.put("preset", parserRun.preset());
        node.put("backend", parserRun.backend());
        node.set("models", MAPPER.valueToTree(parserRun.models()));
        node.set("warnings", MAPPER.valueToTree(parserRun.warnings()));
        if (!parserRun.externalBackend().isEmpty()) {
            node.set("externalBackend", MAPPER.valueToTree(parserRun.externalBackend()));
        }
        if (parserRun.elapsedMs() != null) {
            node.put("elapsedMs", parserRun.elapsedMs());
        }
        return node;
    }

    private static ObjectNode pageNode(TrustPage page) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("pageNumber", page.pageNumber());
        node.put("width", page.width());
        node.put("height", page.height());
        node.put("textLayerAvailable", page.textLayerAvailable());
        node.put("imageHash", page.imageHash());
        return node;
    }

    private static ObjectNode unitNode(TrustUnit unit) {
        ObjectNode node = evidenceUnit(unit);
        node.set("location", unitLocationNode(unit.location()));
        node.put("sourceObjectId", unit.content().sourceObjectId());
        node.set("confidence", MAPPER.valueToTree(unit.evidence().confidence()));
        node.set("warnings", MAPPER.valueToTree(unit.evidence().warnings()));
        return node;
    }

    private static ObjectNode unitLocationNode(TrustUnitLocation location) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("page", location.page());
        node.put("readingOrder", location.readingOrder());
        location.boundingBox().ifPresent(box -> node.set("boundingBox", bboxNode(box)));
        return node;
    }

    private static ObjectNode tableNode(TrustTable table) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tableId", table.tableId());
        node.put("pageNumber", table.pageNumber());
        table.boundingBox().ifPresent(box -> node.set("boundingBox", bboxNode(box)));
        node.set("confidence", MAPPER.valueToTree(table.confidence()));
        ArrayNode cells = MAPPER.createArrayNode();
        table.cells().forEach(cell -> cells.add(cellNode(cell)));
        node.set("cells", cells);
        return node;
    }

    private static ObjectNode cellNode(TrustTableCell cell) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("cellId", cell.cellId());
        node.set("rowRange", MAPPER.valueToTree(cell.rowRange()));
        node.set("columnRange", MAPPER.valueToTree(cell.columnRange()));
        cell.boundingBox().ifPresent(box -> node.set("boundingBox", bboxNode(box)));
        node.put("text", cell.text());
        return node;
    }

    private static ObjectNode bboxNode(BoundingBox box) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("x0", box.x0());
        node.put("y0", box.y0());
        node.put("x1", box.x1());
        node.put("y1", box.y1());
        return node;
    }

    private static ObjectNode pageSizeNode(TrustPage page) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("width", page.width());
        node.put("height", page.height());
        return node;
    }

    private static ArrayNode stringArray(String value) {
        ArrayNode values = MAPPER.createArrayNode();
        values.add(value);
        return values;
    }

    private static String id(String prefix, int index) {
        return "%s-%04d".formatted(prefix, index);
    }

    private static String blockType(TrustUnit unit) {
        return switch (unit.kind()) {
            case HEADING -> "heading";
            case TABLE_CELL -> "table";
            case FIGURE_CAPTION -> "caption";
            default -> "text";
        };
    }

    private static String tableMarkdown(TrustTable table) {
        var rows = rows(table);
        if (rows.isEmpty()) {
            return "";
        }
        var out = new StringBuilder();
        appendMarkdownTableRow(out, rows.getFirst());
        appendMarkdownSeparator(out, rows.getFirst().size());
        rows.stream().skip(1).forEach(row -> appendMarkdownTableRow(out, row));
        return out.toString().stripTrailing();
    }

    private static String tablePlainText(TrustTable table) {
        var rows = rows(table);
        if (rows.isEmpty()) {
            return "";
        }
        return rows.stream()
                .map(row -> row.stream().map(String::strip).collect(Collectors.joining("\t")))
                .collect(Collectors.joining("\n"));
    }

    private static List<List<String>> rows(TrustTable table) {
        int maxRow = table.cells().stream()
                .mapToInt(cell -> cell.rowRange().end())
                .max()
                .orElse(-1);
        int maxCol = table.cells().stream()
                .mapToInt(cell -> cell.columnRange().end())
                .max()
                .orElse(-1);
        var rows = new ArrayList<List<String>>();
        for (int row = 0; row <= maxRow; row++) {
            var values = new ArrayList<String>();
            for (int col = 0; col <= maxCol; col++) {
                values.add(cellText(table, row, col));
            }
            rows.add(values);
        }
        return rows;
    }

    private static String cellText(TrustTable table, int row, int col) {
        return table.cells().stream()
                .filter(cell ->
                        cell.rowRange().start() <= row && cell.rowRange().end() >= row)
                .filter(cell ->
                        cell.columnRange().start() <= col && cell.columnRange().end() >= col)
                .findFirst()
                .map(TrustTableCell::text)
                .orElse("");
    }

    private static TrustTableCell cellAt(TrustTable table, int row, int col) {
        return table.cells().stream()
                .filter(cell ->
                        cell.rowRange().start() <= row && cell.rowRange().end() >= row)
                .filter(cell ->
                        cell.columnRange().start() <= col && cell.columnRange().end() >= col)
                .findFirst()
                .orElseThrow();
    }

    private static void appendMarkdownTableRow(StringBuilder out, List<String> row) {
        out.append("| ");
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                out.append(" | ");
            }
            out.append(markdownCell(row.get(i)));
        }
        out.append(" |\n");
    }

    private static void appendMarkdownSeparator(StringBuilder out, int columns) {
        out.append("| ");
        for (int i = 0; i < columns; i++) {
            if (i > 0) {
                out.append(" | ");
            }
            out.append("---");
        }
        out.append(" |\n");
    }

    private static String markdownCell(String text) {
        return text.replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("|", "\\|")
                .replace('\n', ' ')
                .strip();
    }

    private static String markdownUnit(TrustUnit unit) {
        var text = unit.content().text().strip();
        if (text.isBlank()) {
            return "";
        }
        if (unit.kind() == TrustUnitKind.HEADING && shouldRenderHeading(text)) {
            return "# " + escapeMarkdownHeading(text);
        }
        return text;
    }

    private static boolean shouldRenderHeading(String text) {
        return text.length() <= 120 && !text.contains("\n");
    }

    private static String escapeMarkdownHeading(String text) {
        return text.replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("#", "\\#")
                .strip();
    }

    private static void appendBlock(StringBuilder out, String rendered) {
        if (rendered.isBlank()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append("\n\n");
        }
        out.append(rendered.strip());
    }

    private static void appendCleanBlocksInReadingOrder(
            TrustDocument doc,
            StringBuilder out,
            Function<TrustTable, String> tableRenderer,
            Function<TrustUnit, String> unitRenderer) {
        var emittedTables = new java.util.HashSet<String>();
        for (var unit : sortedUnits(doc)) {
            if (unit.kind() == TrustUnitKind.TABLE_CELL) {
                tableForUnit(doc, unit).ifPresent(table -> {
                    if (emittedTables.add(table.tableId())) {
                        appendBlock(out, tableRenderer.apply(table));
                    }
                });
            } else {
                appendBlock(out, unitRenderer.apply(unit));
            }
        }
        appendTablesWithoutUnits(doc, emittedTables, table -> appendBlock(out, tableRenderer.apply(table)));
    }

    private static void writeCleanBlocksInReadingOrder(
            TrustDocument doc,
            Writer writer,
            boolean[] wrote,
            Function<TrustTable, String> tableRenderer,
            Function<TrustUnit, String> unitRenderer)
            throws IOException {
        var emittedTables = new java.util.HashSet<String>();
        for (var unit : sortedUnits(doc)) {
            if (unit.kind() == TrustUnitKind.TABLE_CELL) {
                var table = tableForUnit(doc, unit);
                if (table.isPresent() && emittedTables.add(table.get().tableId())) {
                    writeBlock(writer, wrote, tableRenderer.apply(table.get()));
                }
            } else {
                writeBlock(writer, wrote, unitRenderer.apply(unit));
            }
        }
        writeTablesWithoutUnits(doc, emittedTables, table -> writeBlock(writer, wrote, tableRenderer.apply(table)));
    }

    private static java.util.Optional<TrustTable> tableForUnit(TrustDocument doc, TrustUnit unit) {
        return doc.body().tables().stream()
                .filter(table -> table.tableId().equals(unit.content().sourceObjectId())
                        || table.cells().stream().anyMatch(cell -> cell.cellId()
                                .equals(unit.content().sourceObjectId())))
                .findFirst();
    }

    private static void appendTablesWithoutUnits(
            TrustDocument doc, java.util.Set<String> emittedTables, java.util.function.Consumer<TrustTable> appender) {
        for (var table : doc.body().tables()) {
            if (emittedTables.add(table.tableId())) {
                appender.accept(table);
            }
        }
    }

    private static void writeTablesWithoutUnits(
            TrustDocument doc, java.util.Set<String> emittedTables, TableAppender appender) throws IOException {
        for (var table : doc.body().tables()) {
            if (emittedTables.add(table.tableId())) {
                appender.append(table);
            }
        }
    }

    private static void writeBlock(Writer writer, boolean[] wrote, String rendered) throws IOException {
        if (rendered.isBlank()) {
            return;
        }
        if (wrote[0]) {
            writeChunked(writer, "\n\n");
        }
        writeChunked(writer, rendered.strip());
        wrote[0] = true;
    }

    private static void appendCompactUnit(StringBuilder out, TrustUnit unit) {
        out.append(compactUnit(unit)).append('\n');
    }

    private static void appendMappedCompactUnit(
            StringBuilder out, List<TrustSourceMapEntry> sourceMap, TrustUnit unit) {
        out.append("u|")
                .append(escape(unit.unitId()))
                .append('|')
                .append(unit.kind())
                .append("|p")
                .append(unit.location().page())
                .append('|')
                .append(escape(String.join(",", unit.evidence().evidenceSpanIds())))
                .append('|');
        int start = out.length();
        out.append(escape(unit.content().text()));
        sourceMap.add(new TrustSourceMapEntry(
                start, out.length(), unit.unitId(), unit.evidence().evidenceSpanIds()));
        unit.location().boundingBox().ifPresent(box -> out.append("|bbox=").append(escape(bboxAttribute(box))));
        out.append('\n');
    }

    private static void appendCompactTable(StringBuilder out, TrustTable table) {
        out.append(compactTable(table)).append('\n');
    }

    private static String compactUnit(TrustUnit unit) {
        var line = new StringBuilder();
        line.append("u|")
                .append(escape(unit.unitId()))
                .append('|')
                .append(unit.kind())
                .append("|p")
                .append(unit.location().page())
                .append('|')
                .append(escape(String.join(",", unit.evidence().evidenceSpanIds())))
                .append('|')
                .append(escape(unit.content().text()));
        unit.location().boundingBox().ifPresent(box -> line.append("|bbox=").append(escape(bboxAttribute(box))));
        return line.toString();
    }

    private static String compactTable(TrustTable table) {
        int rows = table.cells().stream()
                        .mapToInt(cell -> cell.rowRange().end())
                        .max()
                        .orElse(-1)
                + 1;
        int columns = table.cells().stream()
                        .mapToInt(cell -> cell.columnRange().end())
                        .max()
                        .orElse(-1)
                + 1;
        return "t|" + escape(table.tableId()) + "|p" + table.pageNumber() + "|rows=" + rows + "|cols=" + columns;
    }

    private static void appendCompactWarning(StringBuilder out, String scope, ParserWarning warning) {
        out.append(compactWarning(scope, warning)).append('\n');
    }

    private static String compactWarning(String scope, ParserWarning warning) {
        return "w|"
                + escape(scope)
                + "|"
                + warning.severity()
                + "|"
                + escape(warning.code())
                + "|"
                + escape(warning.message());
    }

    private static String anchoredMarkdown(TrustUnit unit) {
        var anchor = new StringBuilder();
        anchor.append(" {#ev:")
                .append(String.join(",", unit.evidence().evidenceSpanIds()))
                .append(" page=")
                .append(unit.location().page());
        unit.location().boundingBox().ifPresent(box -> anchor.append(" bbox=\"")
                .append(bboxAttribute(box))
                .append('"'));
        return unit.content().text().strip() + anchor.append('}');
    }

    private static void appendWarnings(StringBuilder out, List<ParserWarning> warnings) {
        if (warnings.isEmpty()) {
            return;
        }
        appendBlock(out, "## Parser Warnings");
        warnings.forEach(warning ->
                appendBlock(out, "- " + warning.severity() + " " + warning.code() + ": " + warning.message()));
    }

    private static void appendUnitWarnings(StringBuilder out, TrustDocument doc) {
        var warnings = sortedUnits(doc).stream()
                .filter(unit -> !unit.evidence().warnings().isEmpty())
                .toList();
        if (warnings.isEmpty()) {
            return;
        }
        appendBlock(out, "## Unit Warnings");
        warnings.forEach(unit -> unit.evidence()
                .warnings()
                .forEach(warning -> appendBlock(
                        out,
                        "- " + unit.unitId() + " " + warning.severity() + " " + warning.code() + ": "
                                + warning.message())));
    }

    private static void writeWarnings(Writer writer, boolean[] wrote, List<ParserWarning> warnings) throws IOException {
        if (warnings.isEmpty()) {
            return;
        }
        writeBlock(writer, wrote, "## Parser Warnings");
        for (var warning : warnings) {
            writeBlock(writer, wrote, "- " + warning.severity() + " " + warning.code() + ": " + warning.message());
        }
    }

    private static void writeUnitWarnings(Writer writer, boolean[] wrote, TrustDocument doc) throws IOException {
        var warnings = sortedUnits(doc).stream()
                .filter(unit -> !unit.evidence().warnings().isEmpty())
                .toList();
        if (warnings.isEmpty()) {
            return;
        }
        writeBlock(writer, wrote, "## Unit Warnings");
        for (var unit : warnings) {
            for (var warning : unit.evidence().warnings()) {
                writeBlock(
                        writer,
                        wrote,
                        "- "
                                + unit.unitId()
                                + " "
                                + warning.severity()
                                + " "
                                + warning.code()
                                + ": "
                                + warning.message());
            }
        }
    }

    private static void appendMappedBlock(StringBuilder out, List<TrustSourceMapEntry> sourceMap, TrustUnit unit) {
        String rendered = unit.content().text().strip();
        if (rendered.isBlank()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append("\n\n");
        }
        int start = out.length();
        out.append(rendered);
        sourceMap.add(new TrustSourceMapEntry(
                start, out.length(), unit.unitId(), unit.evidence().evidenceSpanIds()));
    }

    private static void appendMappedTable(
            StringBuilder out, List<TrustSourceMapEntry> sourceMap, TrustDocument doc, TrustTable table) {
        var rows = rows(table);
        if (rows.isEmpty()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append("\n\n");
        }
        appendMappedTableRow(out, sourceMap, doc, table, rows.getFirst(), 0);
        appendMarkdownSeparator(out, rows.getFirst().size());
        for (int row = 1; row < rows.size(); row++) {
            appendMappedTableRow(out, sourceMap, doc, table, rows.get(row), row);
        }
    }

    private static void appendMappedTableRow(
            StringBuilder out,
            List<TrustSourceMapEntry> sourceMap,
            TrustDocument doc,
            TrustTable table,
            List<String> rowValues,
            int row) {
        out.append("| ");
        for (int col = 0; col < rowValues.size(); col++) {
            if (col > 0) {
                out.append(" | ");
            }
            var rendered = markdownCell(rowValues.get(col));
            int start = out.length();
            out.append(rendered);
            cellUnit(doc, cellAt(table, row, col))
                    .ifPresent(unit -> sourceMap.add(new TrustSourceMapEntry(
                            start, out.length(), unit.unitId(), unit.evidence().evidenceSpanIds())));
        }
        out.append(" |\n");
    }

    private static java.util.Optional<TrustUnit> cellUnit(TrustDocument doc, TrustTableCell cell) {
        return doc.body().units().stream()
                .filter(unit -> unit.kind() == TrustUnitKind.TABLE_CELL)
                .filter(unit -> unit.content().sourceObjectId().equals(cell.cellId()))
                .findFirst();
    }

    private static void appendHtmlUnit(StringBuilder out, TrustUnit unit) {
        String evidenceIds = unit.evidence().evidenceSpanIds().stream().collect(Collectors.joining(","));
        out.append("  <section data-trust-unit-id=\"")
                .append(html(unit.unitId()))
                .append("\" data-evidence-span-ids=\"")
                .append(html(evidenceIds))
                .append("\" data-page=\"")
                .append(unit.location().page())
                .append("\" data-reading-order=\"")
                .append(unit.location().readingOrder())
                .append("\"");
        unit.location().boundingBox().ifPresent(box -> out.append(" data-bbox=\"")
                .append(html(bboxAttribute(box)))
                .append("\" data-bbox-space=\"normalized-0-1000\""));
        out.append(">").append(html(unit.content().text())).append("</section>\n");
    }

    private static void appendHtmlPageStart(StringBuilder out, TrustPage page) {
        out.append("  <section data-trust-page-number=\"")
                .append(page.pageNumber())
                .append("\" data-page-width=\"")
                .append(html(numberAttribute(page.width())))
                .append("\" data-page-height=\"")
                .append(html(numberAttribute(page.height())))
                .append("\" data-text-layer-available=\"")
                .append(page.textLayerAvailable())
                .append("\" data-image-hash=\"")
                .append(html(page.imageHash()))
                .append("\">\n");
    }

    private static void appendHtmlTable(StringBuilder out, TrustDocument doc, TrustTable table) {
        out.append("  <table data-trust-table-id=\"")
                .append(html(table.tableId()))
                .append("\" data-page=\"")
                .append(table.pageNumber())
                .append("\"");
        table.boundingBox().ifPresent(box -> out.append(" data-bbox=\"")
                .append(html(bboxAttribute(box)))
                .append("\" data-bbox-space=\"normalized-0-1000\""));
        out.append(">\n");
        var rows = rows(table);
        for (int row = 0; row < rows.size(); row++) {
            out.append("    <tr>\n");
            for (int column = 0; column < rows.get(row).size(); column++) {
                appendHtmlCell(out, doc, cellAt(table, row, column));
            }
            out.append("    </tr>\n");
        }
        out.append("  </table>\n");
    }

    private static void appendHtmlCell(StringBuilder out, TrustDocument doc, TrustTableCell cell) {
        out.append("      <td data-trust-cell-id=\"")
                .append(html(cell.cellId()))
                .append("\"");
        cellUnit(doc, cell).ifPresent(unit -> out.append(" data-trust-unit-id=\"")
                .append(html(unit.unitId()))
                .append("\" data-evidence-span-ids=\"")
                .append(html(String.join(",", unit.evidence().evidenceSpanIds())))
                .append("\""));
        cell.boundingBox().ifPresent(box -> out.append(" data-bbox=\"")
                .append(html(bboxAttribute(box)))
                .append("\" data-bbox-space=\"normalized-0-1000\""));
        out.append(">").append(html(cell.text())).append("</td>\n");
    }

    private static void appendHtmlOverlayLayer(StringBuilder out, TrustDocument doc, TrustPage page) {
        out.append("    <div data-trust-overlay-layer=\"bbox\" aria-hidden=\"true\">\n");
        sortedUnits(doc).stream()
                .filter(unit -> unit.location().page() == page.pageNumber())
                .forEach(unit -> unit.location()
                        .boundingBox()
                        .ifPresent(box -> appendHtmlOverlay(out, "unit", unit.unitId(), box)));
        doc.body().tables().stream()
                .filter(table -> table.pageNumber() == page.pageNumber())
                .forEach(table -> {
                    table.boundingBox().ifPresent(box -> appendHtmlOverlay(out, "table", table.tableId(), box));
                    table.cells().forEach(cell -> cell.boundingBox()
                            .ifPresent(box -> appendHtmlOverlay(out, "cell", cell.cellId(), box)));
                });
        out.append("    </div>\n");
    }

    private static void appendHtmlOverlay(StringBuilder out, String kind, String targetId, BoundingBox box) {
        out.append("      <div data-trust-bbox-overlay=\"")
                .append(html(kind))
                .append("\" data-trust-overlay-for=\"")
                .append(html(targetId))
                .append("\" style=\"")
                .append(html(bboxStyle(box)))
                .append("\"></div>\n");
    }

    private static String bboxStyle(BoundingBox box) {
        return "left:"
                + percent(box.x0())
                + ";top:"
                + percent(box.y0())
                + ";width:"
                + percent(box.x1() - box.x0())
                + ";height:"
                + percent(box.y1() - box.y0())
                + ";";
    }

    private static String percent(double normalized) {
        return numberAttribute(normalized / 10.0) + "%";
    }

    private static String bboxAttribute(BoundingBox box) {
        return numberAttribute(box.x0())
                + ","
                + numberAttribute(box.y0())
                + ","
                + numberAttribute(box.x1())
                + ","
                + numberAttribute(box.y1());
    }

    private static String numberAttribute(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return Double.toString(value);
    }

    private static TrustDocumentChunk chunk(
            int index, StringBuilder text, List<String> unitIds, List<String> evidenceIds) {
        return new TrustDocumentChunk(
                "chunk-%04d".formatted(index),
                text.toString(),
                List.copyOf(unitIds),
                evidenceIds.stream().distinct().toList());
    }

    private static void appendJsonLine(StringBuilder out, ObjectNode node) {
        out.append(compact(node)).append('\n');
    }

    private static void writeJsonLine(Writer writer, ObjectNode node) throws IOException {
        writeChunked(writer, compact(node));
        writeChunked(writer, "\n");
    }

    private static void writeJson(Writer writer, JsonNode node) throws IOException {
        MAPPER.writeValue(new ChunkingWriter(writer), node);
    }

    private static void writeSourceMapJson(Writer writer, RenderedSourceMap rendered) throws IOException {
        writeJson(writer, sourceMapNode(rendered));
    }

    private static ObjectNode sourceMapNode(RenderedSourceMap rendered) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", rendered.format());
        root.put("text", rendered.text());
        root.put("sourceHash", rendered.sourceHash());
        root.put("contentHash", rendered.contentHash());
        ArrayNode entries = MAPPER.createArrayNode();
        for (var entry : rendered.sourceMap()) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("startOffset", entry.startOffset());
            node.put("endOffset", entry.endOffset());
            node.put("unitId", entry.unitId());
            ArrayNode evidenceSpanIds = MAPPER.createArrayNode();
            entry.evidenceSpanIds().forEach(evidenceSpanIds::add);
            node.set("evidenceSpanIds", evidenceSpanIds);
            entries.add(node);
        }
        root.set("sourceMap", entries);
        return root;
    }

    private static void writeFragment(Writer writer, FragmentAppender appender) throws IOException {
        var out = new StringBuilder();
        appender.append(out);
        writeChunked(writer, out.toString());
    }

    private static List<TrustUnit> sortedUnits(TrustDocument doc) {
        return doc.body().units().stream()
                .sorted(Comparator.comparingInt(unit -> unit.location().readingOrder()))
                .toList();
    }

    private static List<TrustUnit> cleanMarkdownUnits(TrustDocument doc) {
        return doc.body().units().stream()
                .filter(unit -> unit.kind() != TrustUnitKind.TABLE_CELL)
                .sorted(Comparator.comparingInt(unit -> unit.location().readingOrder()))
                .toList();
    }

    private static void writeChunked(Writer writer, String value) throws IOException {
        int offset = 0;
        while (offset < value.length()) {
            int end = Math.min(offset + STREAM_WRITE_CHARS, value.length());
            writer.write(value, offset, end - offset);
            offset = end;
        }
    }

    @FunctionalInterface
    private interface FragmentAppender {
        void append(StringBuilder out);
    }

    @FunctionalInterface
    private interface TableAppender {
        void append(TrustTable table) throws IOException;
    }

    private record RenderedSourceMap(
            String format, String text, String sourceHash, String contentHash, List<TrustSourceMapEntry> sourceMap) {}

    private static final class ChunkingWriter extends Writer {

        private final Writer delegate;

        ChunkingWriter(Writer delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            int offset = off;
            int remaining = len;
            while (remaining > 0) {
                int next = Math.min(STREAM_WRITE_CHARS, remaining);
                delegate.write(cbuf, offset, next);
                offset += next;
                remaining -= next;
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.flush();
        }
    }

    private static String compact(JsonNode root) {
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render evidence JSON", e);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n");
    }

    private static String html(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be supported by every JDK", e);
        }
    }

    private static String sha256(HashInputWriter input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var writer = new OutputStreamWriter(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest), StandardCharsets.UTF_8)) {
                input.write(writer);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("failed to hash TrustDocument", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be supported by every JDK", e);
        }
    }

    @FunctionalInterface
    private interface HashInputWriter {
        void write(Writer writer) throws IOException;
    }
}
