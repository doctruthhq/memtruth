package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.doctruth.BlockKind;
import ai.doctruth.BoundingBox;
import ai.doctruth.DocumentMetadata;
import ai.doctruth.ParserRun;
import ai.doctruth.ParsedDocument;
import ai.doctruth.ParsedSection;
import ai.doctruth.SourceLocation;
import ai.doctruth.TextSection;
import ai.doctruth.TrustDocument;
import ai.doctruth.TrustRenderedDocument;

import org.junit.jupiter.api.Test;

/** CLI file-output contracts for large TrustDocument render paths. */
class TrustDocumentCliWritersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void cliJsonFullAndAuditFileWritersDoNotWriteWholeOutputAtOnce() throws Exception {
        var doc = largeDocument();
        var jsonFull = new MaxWriteSizeWriter(512);
        var audit = new MaxWriteSizeWriter(512);

        TrustDocumentCliWriters.writeJsonFull(doc, jsonFull);
        TrustDocumentCliWriters.writeAuditJson(doc, audit);

        assertThat(jsonFull.toString()).isEqualTo(doc.toJsonFull());
        assertThat(audit.toString()).isEqualTo(doc.toAuditJson());
        assertThat(jsonFull.largestWrite()).isLessThan(512);
        assertThat(audit.largestWrite()).isLessThan(512);
    }

    @Test
    void cliJsonEvidenceStillUsesAStableMachineReadableWriterBoundary() throws Exception {
        var doc = largeDocument();
        var writer = new StringWriter();

        TrustDocumentCliWriters.writeJsonEvidence(doc, writer);

        assertThat(writer.toString()).isEqualTo(doc.toJsonEvidence());
        assertThat(writer.toString()).contains("\"sourceHash\":\"sha256:cli-large\"");
    }

    @Test
    void cliStdoutWriterDoesNotWriteWholeOutputAtOnce() throws Exception {
        var doc = largeDocument();
        var out = new MaxWriteSizeOutputStream(512);
        var stream = new PrintStream(out, true, StandardCharsets.UTF_8);

        TrustDocumentCliWriters.writeToPrintStream(
                stream, writer -> TrustDocumentCliWriters.writeMarkdownReview(doc, writer));

        assertThat(out.toString()).isEqualTo(doc.toMarkdownReview());
        assertThat(out.largestWrite()).isLessThan(512);
    }

    @Test
    void cliSourceMapWriterDoesNotWriteWholeOutputAtOnce() throws Exception {
        TrustRenderedDocument rendered = largeDocument().toMarkdownWithSourceMap();
        var writer = new MaxWriteSizeWriter(512);

        TrustDocumentCliWriters.writeSourceMap(rendered, writer);

        assertThat(writer.toString()).contains("\"format\":\"markdown\"");
        assertThat(writer.toString()).contains("\"sourceMap\":[");
        assertThat(writer.largestWrite()).isLessThan(512);
    }

    @Test
    void cliCanWriteSourceMapsDirectlyFromTrustDocument() throws Exception {
        var doc = largeDocument();
        var markdown = new MaxWriteSizeWriter(512);
        var compact = new MaxWriteSizeWriter(512);

        TrustDocumentCliWriters.writeMarkdownSourceMap(doc, markdown);
        TrustDocumentCliWriters.writeCompactLlmSourceMap(doc, compact);

        assertThat(markdown.toString()).contains("\"format\":\"markdown\"");
        assertThat(markdown.toString()).contains("\"sourceMap\":[");
        assertThat(compact.toString()).contains("\"format\":\"compact_llm\"");
        assertThat(compact.toString()).contains("\"sourceMap\":[");
        assertThat(markdown.largestWrite()).isLessThan(512);
        assertThat(compact.largestWrite()).isLessThan(512);
    }

    @Test
    void parseTraceUsesDocumentParserRunId() throws Exception {
        var doc = largeDocument(new ParserRun(
                "parser-run-rust-42", "1.0.0", "lite", "rust-sidecar", List.of("layout:v2"), List.of()));
        var writer = new StringWriter();

        TrustDocumentCliWriters.writeParseTrace(doc, writer);

        assertThat(MAPPER.readTree(writer.toString()).path("parseTrace").path("parserRunId").asText())
                .isEqualTo("parser-run-rust-42");
    }

    private static TrustDocument largeDocument() {
        return largeDocument(new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of()));
    }

    private static TrustDocument largeDocument(ParserRun parserRun) {
        var sections = new ArrayList<ParsedSection>();
        for (int i = 0; i < 80; i++) {
            sections.add(new TextSection(
                    "CLI writer block %02d should stay in the file writer path.".formatted(i),
                    new SourceLocation(1, 1, i + 1, i + 1, i * 64),
                    BlockKind.BODY,
                    Optional.of(new BoundingBox(0, i, 900, i + 8))));
        }
        var parsed = new ParsedDocument(
                "doc-cli-large",
                sections,
                new DocumentMetadata("cli-large.pdf", 1, Optional.empty()));
        return TrustDocument.fromParsed(parsed, "sha256:cli-large", parserRun)
                .withEvaluatedAuditGrade();
    }

    private static final class MaxWriteSizeWriter extends Writer {

        private final StringBuilder out = new StringBuilder();
        private final int maxWriteSize;
        private int largestWrite;

        MaxWriteSizeWriter(int maxWriteSize) {
            this.maxWriteSize = maxWriteSize;
        }

        int largestWrite() {
            return largestWrite;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            if (len >= maxWriteSize) {
                throw new IOException("write too large: " + len);
            }
            largestWrite = Math.max(largestWrite, len);
            out.append(cbuf, off, len);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        @Override
        public String toString() {
            return out.toString();
        }
    }

    private static final class MaxWriteSizeOutputStream extends OutputStream {

        private final StringBuilder out = new StringBuilder();
        private final int maxWriteSize;
        private int largestWrite;

        MaxWriteSizeOutputStream(int maxWriteSize) {
            this.maxWriteSize = maxWriteSize;
        }

        int largestWrite() {
            return largestWrite;
        }

        @Override
        public void write(int b) {
            largestWrite = Math.max(largestWrite, 1);
            out.append((char) b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len >= maxWriteSize) {
                throw new IOException("write too large: " + len);
            }
            largestWrite = Math.max(largestWrite, len);
            out.append(new String(b, off, len, StandardCharsets.UTF_8));
        }

        @Override
        public String toString() {
            return out.toString();
        }
    }
}
