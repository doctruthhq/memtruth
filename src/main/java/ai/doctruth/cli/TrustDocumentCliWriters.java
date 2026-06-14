package ai.doctruth.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import ai.doctruth.TrustDocument;
import ai.doctruth.TrustRenderedDocument;
import ai.doctruth.TrustUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

final class TrustDocumentCliWriters {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private TrustDocumentCliWriters() {
        throw new AssertionError("no instances");
    }

    static void writeJsonFull(TrustDocument document, Writer writer) throws IOException {
        document.writeJsonFull(writer);
    }

    static void writeJsonEvidence(TrustDocument document, Writer writer) throws IOException {
        document.writeJsonEvidence(writer);
    }

    static void writeAuditJson(TrustDocument document, Writer writer) throws IOException {
        document.writeAuditJson(writer);
    }

    static void writeJsonLines(TrustDocument document, Writer writer) throws IOException {
        document.writeJsonLines(writer);
    }

    static void writeCompactLlm(TrustDocument document, Writer writer) throws IOException {
        document.writeCompactLlm(writer);
    }

    static void writeMarkdownClean(TrustDocument document, Writer writer) throws IOException {
        document.writeMarkdownClean(writer);
    }

    static void writeMarkdownAnchored(TrustDocument document, Writer writer) throws IOException {
        document.writeMarkdownAnchored(writer);
    }

    static void writeMarkdownReview(TrustDocument document, Writer writer) throws IOException {
        document.writeMarkdownReview(writer);
    }

    static void writePlainText(TrustDocument document, Writer writer) throws IOException {
        document.writePlainText(writer);
    }

    static void writeHtmlReview(TrustDocument document, Writer writer) throws IOException {
        document.writeHtmlReview(writer);
    }

    static void writeContentBlocks(TrustDocument document, Writer writer) throws IOException {
        MAPPER.writeValue(new ChunkedWriter(writer), contentBlocksRoot(document));
    }

    static void writeParseTrace(TrustDocument document, Writer writer) throws IOException {
        MAPPER.writeValue(new ChunkedWriter(writer), parseTraceRoot(document));
    }

    static void writeLayoutDebugHtml(TrustDocument document, Writer writer) throws IOException {
        writer.write("<!doctype html>\n<html><body data-doctruth-debug-artifact=\"layout\">\n");
        for (var unit : document.body().units()) {
            writer.write(layoutDebugNode(unit));
        }
        writer.write("</body></html>\n");
    }

    static void writeSpanDebugHtml(TrustDocument document, Writer writer) throws IOException {
        writer.write("<!doctype html>\n<html><body data-doctruth-debug-artifact=\"span\">\n");
        for (var unit : document.body().units()) {
            writer.write(spanDebugNode(unit));
        }
        writer.write("</body></html>\n");
    }

    static void writeSourceMap(TrustRenderedDocument rendered, Writer writer) throws IOException {
        MAPPER.writeValue(new ChunkedWriter(writer), rendered);
    }

    static void writeMarkdownSourceMap(TrustDocument document, Writer writer) throws IOException {
        document.writeMarkdownSourceMap(writer);
    }

    static void writeCompactLlmSourceMap(TrustDocument document, Writer writer) throws IOException {
        document.writeCompactLlmSourceMap(writer);
    }

    static void writeToFile(Path out, WriterOperation operation) throws CliException {
        try {
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                operation.write(writer);
            }
        } catch (IOException e) {
            throw new CliException("failed to write parsed output: " + e.getMessage(), e);
        }
    }

    static void writeToPrintStream(PrintStream out, WriterOperation operation) throws CliException {
        try {
            var writer = new PrintStreamWriter(out);
            operation.write(writer);
            writer.flush();
        } catch (IOException e) {
            throw new CliException("failed to write parsed output: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    interface WriterOperation {
        void write(Writer writer) throws IOException;
    }

    private static ObjectNode contentBlocksRoot(TrustDocument document) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", "doctruth.content_blocks.v1");
        root.put("docId", document.docId());
        root.put("sourceHash", document.source().sourceHash());
        root.set("contentBlocks", contentBlocks(document));
        return root;
    }

    private static ObjectNode parseTraceRoot(TrustDocument document) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", "doctruth.parse_trace.v1");
        root.put("docId", document.docId());
        root.put("sourceHash", document.source().sourceHash());
        root.set("parseTrace", parseTrace(document));
        return root;
    }

    private static ArrayNode contentBlocks(TrustDocument document) {
        ArrayNode blocks = MAPPER.createArrayNode();
        document.body().units().forEach(unit -> blocks.add(contentBlock(unit)));
        return blocks;
    }

    private static ObjectNode contentBlock(TrustUnit unit) {
        int readingOrder = unit.location().readingOrder();
        ObjectNode block = MAPPER.createObjectNode();
        block.put("blockId", id("block", readingOrder));
        block.put("type", blockType(unit));
        block.put("page", unit.location().page());
        unit.location().boundingBox().ifPresent(bbox -> block.set("bbox", bboxNode(bbox.x0(), bbox.y0(), bbox.x1(), bbox.y1())));
        block.put("readingOrder", readingOrder);
        block.put("text", unit.content().text());
        block.set("sourceUnitIds", stringArray(unit.unitId()));
        block.set("evidenceSpanIds", stringArray(unit.evidence().evidenceSpanIds()));
        block.set("warnings", warningCodes(unit));
        return block;
    }

    private static ObjectNode parseTrace(TrustDocument document) {
        ObjectNode trace = MAPPER.createObjectNode();
        trace.put("traceId", "trace-0001");
        trace.put("parserRunId", document.parserRun().parserRunId());
        ArrayNode pages = MAPPER.createArrayNode();
        document.body().pages().forEach(page -> {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("pageIndex", page.pageNumber() - 1);
            node.put("pageNumber", page.pageNumber());
            node.set("pageSize", pageSizeNode(page.width(), page.height()));
            node.set("preprocBlocks", MAPPER.createArrayNode());
            ArrayNode readingBlocks = MAPPER.createArrayNode();
            document.body().units().stream()
                    .filter(unit -> unit.location().page() == page.pageNumber())
                    .forEach(unit -> readingBlocks.add(traceBlock(unit)));
            node.set("readingBlocks", readingBlocks);
            node.set("discardedBlocks", MAPPER.createArrayNode());
            node.set("images", MAPPER.createArrayNode());
            node.set("tables", MAPPER.createArrayNode());
            node.set("equations", MAPPER.createArrayNode());
            pages.add(node);
        });
        trace.set("pages", pages);
        trace.set("warnings", MAPPER.createArrayNode());
        return trace;
    }

    private static ObjectNode traceBlock(TrustUnit unit) {
        int readingOrder = unit.location().readingOrder();
        ObjectNode block = MAPPER.createObjectNode();
        block.put("blockId", id("block", readingOrder));
        block.put("type", blockType(unit));
        unit.location().boundingBox().ifPresent(bbox -> block.set("bbox", bboxNode(bbox.x0(), bbox.y0(), bbox.x1(), bbox.y1())));
        block.put("readingOrder", readingOrder);
        block.put("confidence", unit.evidence().confidence().score());
        block.put("modelRunId", "");
        block.set("sourceUnitIds", stringArray(unit.unitId()));
        block.set("evidenceSpanIds", stringArray(unit.evidence().evidenceSpanIds()));
        block.set("warnings", warningCodes(unit));
        block.set("lines", traceLines(unit));
        return block;
    }

    private static ArrayNode traceLines(TrustUnit unit) {
        int readingOrder = unit.location().readingOrder();
        ObjectNode line = MAPPER.createObjectNode();
        line.put("lineId", id("line", readingOrder));
        unit.location().boundingBox().ifPresent(bbox -> line.set("bbox", bboxNode(bbox.x0(), bbox.y0(), bbox.x1(), bbox.y1())));
        line.put("text", unit.content().text());
        line.set("spans", traceSpans(unit));
        ArrayNode lines = MAPPER.createArrayNode();
        lines.add(line);
        return lines;
    }

    private static ArrayNode traceSpans(TrustUnit unit) {
        int readingOrder = unit.location().readingOrder();
        String evidenceSpanId = unit.evidence().evidenceSpanIds().isEmpty() ? "" : unit.evidence().evidenceSpanIds().get(0);
        ObjectNode span = MAPPER.createObjectNode();
        span.put("spanId", id("trace-span", readingOrder));
        span.put("type", "text");
        span.put("content", unit.content().text());
        unit.location().boundingBox().ifPresent(bbox -> span.set("bbox", bboxNode(bbox.x0(), bbox.y0(), bbox.x1(), bbox.y1())));
        span.put("score", unit.evidence().confidence().score());
        span.put("sourceObjectId", unit.content().sourceObjectId());
        span.put("evidenceSpanId", evidenceSpanId);
        ArrayNode spans = MAPPER.createArrayNode();
        spans.add(span);
        return spans;
    }

    private static String blockType(TrustUnit unit) {
        return switch (unit.kind()) {
            case TABLE_CELL -> "table";
            case FIGURE_CAPTION -> "image";
            case OCR_REGION -> "text";
            default -> "text";
        };
    }

    private static ObjectNode bboxNode(double x0, double y0, double x1, double y1) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("x0", x0);
        node.put("y0", y0);
        node.put("x1", x1);
        node.put("y1", y1);
        return node;
    }

    private static ObjectNode pageSizeNode(double width, double height) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("width", width);
        node.put("height", height);
        return node;
    }

    private static String layoutDebugNode(TrustUnit unit) {
        String blockId = id("block", unit.location().readingOrder());
        return "  <section data-trace-block-id=\""
                + html(blockId)
                + "\" data-trust-unit-id=\""
                + html(unit.unitId())
                + "\">"
                + html(unit.content().text())
                + "</section>\n";
    }

    private static String spanDebugNode(TrustUnit unit) {
        int readingOrder = unit.location().readingOrder();
        return "  <span data-trace-block-id=\""
                + html(id("block", readingOrder))
                + "\" data-trace-line-id=\""
                + html(id("line", readingOrder))
                + "\" data-trace-span-id=\""
                + html(id("trace-span", readingOrder))
                + "\" data-trust-unit-id=\""
                + html(unit.unitId())
                + "\">"
                + html(unit.content().text())
                + "</span>\n";
    }

    private static String html(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static ArrayNode stringArray(String value) {
        ArrayNode array = MAPPER.createArrayNode();
        array.add(value);
        return array;
    }

    private static ArrayNode stringArray(java.util.List<String> values) {
        ArrayNode array = MAPPER.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private static ArrayNode warningCodes(TrustUnit unit) {
        ArrayNode array = MAPPER.createArrayNode();
        unit.evidence().warnings().forEach(warning -> array.add(warning.code()));
        return array;
    }

    private static String id(String prefix, int index) {
        return String.format("%s-%04d", prefix, index);
    }

    private static final class PrintStreamWriter extends Writer {
        private static final int MAX_CHARS_PER_WRITE = 256;

        private final PrintStream out;

        private PrintStreamWriter(PrintStream out) {
            this.out = out;
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            var remaining = CharBuffer.wrap(cbuf, off, len);
            while (remaining.hasRemaining()) {
                int size = Math.min(MAX_CHARS_PER_WRITE, remaining.remaining());
                char[] chunk = new char[size];
                remaining.get(chunk);
                out.print(new String(chunk));
            }
        }

        @Override
        public void flush() {
            out.flush();
        }

        @Override
        public void close() {
            flush();
        }
    }

    private static final class ChunkedWriter extends Writer {
        private static final int MAX_CHARS_PER_WRITE = 256;

        private final Writer delegate;

        private ChunkedWriter(Writer delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            int cursor = off;
            int end = off + len;
            while (cursor < end) {
                int size = Math.min(MAX_CHARS_PER_WRITE, end - cursor);
                delegate.write(cbuf, cursor, size);
                cursor += size;
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
