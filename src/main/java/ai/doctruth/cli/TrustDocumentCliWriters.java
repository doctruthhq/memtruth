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
        document.writeContentBlocks(writer);
    }

    static void writeParseTrace(TrustDocument document, Writer writer) throws IOException {
        document.writeParseTrace(writer);
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
