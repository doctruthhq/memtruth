package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract tests for writer-based output paths used by large documents. */
class TrustDocumentStreamingRenderContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("TrustDocument can stream clean Markdown, JSONL, compact, source maps, JSON full/evidence, and audit JSON")
    void streamingWritersProduceExpectedOutput() throws Exception {
        var doc = document();
        var markdown = new StringWriter();
        var markdownAnchored = new StringWriter();
        var markdownReview = new StringWriter();
        var plainText = new StringWriter();
        var jsonl = new StringWriter();
        var compact = new StringWriter();
        var jsonFull = new StringWriter();
        var jsonEvidence = new StringWriter();
        var audit = new StringWriter();
        var htmlReview = new StringWriter();
        var markdownSourceMap = new StringWriter();
        var compactSourceMap = new StringWriter();

        doc.writeMarkdownClean(markdown);
        doc.writeMarkdownAnchored(markdownAnchored);
        doc.writeMarkdownReview(markdownReview);
        doc.writePlainText(plainText);
        doc.writeJsonLines(jsonl);
        doc.writeCompactLlm(compact);
        doc.writeJsonFull(jsonFull);
        doc.writeJsonEvidence(jsonEvidence);
        doc.writeAuditJson(audit);
        doc.writeHtmlReview(htmlReview);
        doc.writeMarkdownSourceMap(markdownSourceMap);
        doc.writeCompactLlmSourceMap(compactSourceMap);

        assertThat(markdown.toString()).isEqualTo(doc.toMarkdownClean());
        assertThat(markdownAnchored.toString()).isEqualTo(doc.toMarkdownAnchored());
        assertThat(markdownReview.toString()).isEqualTo(doc.toMarkdownReview());
        assertThat(plainText.toString()).isEqualTo(doc.toPlainText());
        assertThat(jsonl.toString()).isEqualTo(doc.toJsonLines());
        assertThat(compact.toString()).isEqualTo(doc.toCompactLlm());
        assertThat(jsonFull.toString()).isEqualTo(doc.toJsonFull());
        assertThat(jsonEvidence.toString()).isEqualTo(doc.toJsonEvidence());
        assertThat(audit.toString()).isEqualTo(doc.toAuditJson());
        assertThat(htmlReview.toString()).isEqualTo(doc.toHtmlReview());
        assertThat(markdownSourceMap.toString()).isEqualTo(renderedJson(doc.toMarkdownWithSourceMap()));
        assertThat(compactSourceMap.toString()).isEqualTo(renderedJson(doc.toCompactLlmWithSourceMap()));
        assertThat(jsonl.toString()).contains("\"unit_id\":\"unit-0001\"");
        assertThat(compact.toString()).contains("u|unit-0001").contains("|bbox=");
        assertThat(jsonFull.toString()).contains("\"parserRun\"");
        assertThat(jsonEvidence.toString()).contains("\"sourceHash\"");
        assertThat(audit.toString()).contains("\"evidenceHash\"");
        assertThat(htmlReview.toString()).contains("data-trust-page-number=\"1\"");
    }

    @Test
    @DisplayName("writer paths do not require one aggregate in-memory render")
    void writerPathsDoNotWriteWholeDocumentAtOnce() throws Exception {
        var doc = largeDocument();
        var markdown = new MaxWriteSizeWriter(512);
        var markdownAnchored = new MaxWriteSizeWriter(512);
        var markdownReview = new MaxWriteSizeWriter(512);
        var plainText = new MaxWriteSizeWriter(512);
        var jsonl = new MaxWriteSizeWriter(512);
        var compact = new MaxWriteSizeWriter(512);
        var jsonFull = new MaxWriteSizeWriter(512);
        var jsonEvidence = new MaxWriteSizeWriter(512);
        var audit = new MaxWriteSizeWriter(512);
        var htmlReview = new MaxWriteSizeWriter(512);
        var markdownSourceMap = new MaxWriteSizeWriter(512);
        var compactSourceMap = new MaxWriteSizeWriter(512);

        doc.writeMarkdownClean(markdown);
        doc.writeMarkdownAnchored(markdownAnchored);
        doc.writeMarkdownReview(markdownReview);
        doc.writePlainText(plainText);
        doc.writeJsonLines(jsonl);
        doc.writeCompactLlm(compact);
        doc.writeJsonFull(jsonFull);
        doc.writeJsonEvidence(jsonEvidence);
        doc.writeAuditJson(audit);
        doc.writeHtmlReview(htmlReview);
        doc.writeMarkdownSourceMap(markdownSourceMap);
        doc.writeCompactLlmSourceMap(compactSourceMap);

        assertThat(markdown.toString()).isEqualTo(doc.toMarkdownClean());
        assertThat(markdownAnchored.toString()).isEqualTo(doc.toMarkdownAnchored());
        assertThat(markdownReview.toString()).isEqualTo(doc.toMarkdownReview());
        assertThat(plainText.toString()).isEqualTo(doc.toPlainText());
        assertThat(jsonl.toString()).isEqualTo(doc.toJsonLines());
        assertThat(compact.toString()).isEqualTo(doc.toCompactLlm());
        assertThat(jsonFull.toString()).isEqualTo(doc.toJsonFull());
        assertThat(jsonEvidence.toString()).isEqualTo(doc.toJsonEvidence());
        assertThat(audit.toString()).isEqualTo(doc.toAuditJson());
        assertThat(htmlReview.toString()).isEqualTo(doc.toHtmlReview());
        assertThat(markdownSourceMap.toString()).isEqualTo(renderedJson(doc.toMarkdownWithSourceMap()));
        assertThat(compactSourceMap.toString()).isEqualTo(renderedJson(doc.toCompactLlmWithSourceMap()));
        assertThat(markdown.largestWrite()).isLessThan(512);
        assertThat(markdownAnchored.largestWrite()).isLessThan(512);
        assertThat(markdownReview.largestWrite()).isLessThan(512);
        assertThat(plainText.largestWrite()).isLessThan(512);
        assertThat(jsonl.largestWrite()).isLessThan(512);
        assertThat(compact.largestWrite()).isLessThan(512);
        assertThat(jsonFull.largestWrite()).isLessThan(512);
        assertThat(jsonEvidence.largestWrite()).isLessThan(512);
        assertThat(audit.largestWrite()).isLessThan(512);
        assertThat(htmlReview.largestWrite()).isLessThan(512);
        assertThat(markdownSourceMap.largestWrite()).isLessThan(512);
        assertThat(compactSourceMap.largestWrite()).isLessThan(512);
    }

    @Test
    @DisplayName("canonical and evidence hash inputs have writer boundaries")
    void hashInputsDoNotWriteWholeDocumentAtOnce() throws Exception {
        var doc = largeDocument();
        var canonical = new MaxWriteSizeWriter(512);
        var evidence = new MaxWriteSizeWriter(512);

        TrustDocumentRenderers.writeCanonicalHashInput(doc, canonical);
        TrustDocumentRenderers.writeEvidenceHashInput(doc, evidence);

        assertThat(canonical.toString()).isEqualTo(doc.toJsonFull());
        assertThat(evidence.toString()).contains("\"unitId\":\"unit-0001\"");
        assertThat(canonical.largestWrite()).isLessThan(512);
        assertThat(evidence.largestWrite()).isLessThan(512);
    }

    @Test
    @DisplayName("HTML review renders one overlay layer per page")
    void htmlReviewRendersOneOverlayLayerPerPage() {
        var html = document().toHtmlReview();

        assertThat(html.split("data-trust-overlay-layer=\\\"bbox\\\"", -1).length - 1).isEqualTo(1);
    }

    private static TrustDocument document() {
        var parsed = new ParsedDocument(
                "doc-stream",
                List.of(new TextSection(
                        "Streaming renderer keeps output caller-owned.",
                        new SourceLocation(1, 1, 1, 1, 0),
                        BlockKind.BODY,
                        Optional.of(new BoundingBox(0, 0, 1000, 100)))),
                new DocumentMetadata("stream.pdf", 1, Optional.empty()));
        return TrustDocument.fromParsed(
                        parsed, "sha256:stream", new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of()))
                .withEvaluatedAuditGrade();
    }

    private static TrustDocument largeDocument() {
        var sections = new ArrayList<ParsedSection>();
        for (int i = 0; i < 80; i++) {
            sections.add(new TextSection(
                    "Streaming block %02d keeps rendering incremental and caller-owned.".formatted(i),
                    new SourceLocation(1, 1, i + 1, i + 1, i * 64),
                    BlockKind.BODY,
                    Optional.of(new BoundingBox(0, i, 900, i + 8))));
        }
        var parsed = new ParsedDocument(
                "doc-stream-large",
                sections,
                new DocumentMetadata("stream-large.pdf", 1, Optional.empty()));
        return TrustDocument.fromParsed(
                        parsed, "sha256:stream-large", new ParserRun("1.0.0", "lite", "pdfbox", List.of(), List.of()))
                .withEvaluatedAuditGrade();
    }

    private static String renderedJson(TrustRenderedDocument rendered) throws IOException {
        return MAPPER.writeValueAsString(rendered);
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
}
