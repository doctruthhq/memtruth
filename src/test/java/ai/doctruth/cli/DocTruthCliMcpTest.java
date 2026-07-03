package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocTruthCliMcpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void mcpListsAndCallsParseDocumentEvidenceTool() throws Exception {
        Path pdf = samplePdf();
        Path runtime = fakeMcpRuntime();
        var cli = cli("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test-agent","version":"1"}}}
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"doctruth.parse_document","arguments":{"path":"%s","format":"compact_llm","sourceMap":true}}}
                """.formatted(jsonEscape(pdf.toString())));

        int code =
                withSystemProperty("doctruth.runtime.command", runtime.toString(), () -> cli.run(new String[] {"mcp"}));

        assertThat(code).isZero();
        var lines = cli.out().lines().map(DocTruthCliMcpTest::readJson).toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).path("result").path("serverInfo").path("name").asText())
                .isEqualTo("doctruth");
        assertThat(lines.get(1).path("result").path("tools").get(0).path("name").asText())
                .isEqualTo("doctruth.parse_document");

        JsonNode result = lines.get(2).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
        JsonNode structured = result.path("structuredContent");
        assertThat(structured.path("docId").asText()).startsWith("sha256:");
        assertThat(structured.path("format").asText()).isEqualTo("compact_llm");
        assertThat(structured.path("compact").asText()).contains("MCP Rust Runtime Evidence Contract");
        assertThat(structured.path("jsonEvidence").path("units")).isNotEmpty();
        assertThat(structured
                        .path("jsonEvidence")
                        .path("units")
                        .get(0)
                        .path("evidenceSpanIds")
                        .get(0)
                        .asText())
                .startsWith("span-");
        assertThat(structured
                        .path("jsonEvidence")
                        .path("units")
                        .get(0)
                        .path("location")
                        .path("boundingBox")
                        .isObject())
                .isTrue();
        assertThat(structured
                        .path("sourceMap")
                        .path("sourceMap")
                        .get(0)
                        .path("unitId")
                        .asText())
                .startsWith("unit-");
    }

    @Test
    void mcpRejectsUnknownToolWithJsonRpcError() throws Exception {
        var cli = cli("""
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"doctruth.nope","arguments":{}}}
                """);

        int code = cli.run(new String[] {"mcp"});

        assertThat(code).isZero();
        JsonNode response = readJson(cli.out().strip());
        assertThat(response.path("id").asInt()).isEqualTo(9);
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asText()).contains("unknown MCP tool");
    }

    @Test
    void mcpListsEvidenceLayoutTableAndCitationTools() {
        var cli = cli("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);

        int code = cli.run(new String[] {"mcp"});

        assertThat(code).isZero();
        JsonNode tools = readJson(cli.out().strip()).path("result").path("tools");
        assertThat(tools.findValuesAsText("name"))
                .contains(
                        "doctruth.parse_document",
                        "doctruth.get_layout_regions",
                        "doctruth.get_table_cells",
                        "doctruth.get_evidence_span",
                        "doctruth.verify_citation",
                        "doctruth.warm_model_cache");
    }

    @Test
    void mcpEvidenceToolsReturnLayoutTableSpanAndCitationVerification() throws Exception {
        Path textPdf = samplePdf();
        Path tablePdf = tablePdf();
        Path runtime = fakeMcpRuntime();
        var cli = cli("""
                {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"doctruth.get_layout_regions","arguments":{"path":"%s"}}}
                {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"doctruth.get_table_cells","arguments":{"path":"%s"}}}
                {"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"doctruth.get_evidence_span","arguments":{"path":"%s","evidenceSpanId":"span-0001"}}}
                {"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"doctruth.verify_citation","arguments":{"path":"%s","quote":"MCP Rust Runtime Evidence Contract","evidenceSpanId":"span-0001"}}}
                """.formatted(
                        jsonEscape(textPdf.toString()),
                        jsonEscape(tablePdf.toString()),
                        jsonEscape(textPdf.toString()),
                        jsonEscape(textPdf.toString())));

        int code =
                withSystemProperty("doctruth.runtime.command", runtime.toString(), () -> cli.run(new String[] {"mcp"}));

        assertThat(code).isZero();
        var lines = cli.out().lines().map(DocTruthCliMcpTest::readJson).toList();
        assertThat(lines).hasSize(4);
        JsonNode regions = lines.get(0).path("result").path("structuredContent").path("regions");
        assertThat(regions).isNotEmpty();
        assertThat(regions.get(0).path("unitId").asText()).startsWith("unit-");
        assertThat(regions.get(0).path("boundingBox").isObject()).isTrue();

        JsonNode cells = lines.get(1)
                .path("result")
                .path("structuredContent")
                .path("tables")
                .get(0)
                .path("cells");
        assertThat(cells).isNotEmpty();
        assertThat(cells.findValuesAsText("text")).contains("Name", "Score", "Alex", "98");
        assertThat(cells.get(0).path("boundingBox").isObject()).isTrue();

        JsonNode span = lines.get(2).path("result").path("structuredContent").path("span");
        assertThat(span.path("evidenceSpanId").asText()).isEqualTo("span-0001");
        assertThat(span.path("text").asText()).contains("MCP Rust Runtime Evidence Contract");
        assertThat(span.path("boundingBox").isObject()).isTrue();

        JsonNode verification =
                lines.get(3).path("result").path("structuredContent").path("verification");
        assertThat(verification.path("verified").asBoolean()).isTrue();
        assertThat(verification.path("matchScore").asDouble()).isEqualTo(1.0);
        assertThat(verification.path("evidenceSpanId").asText()).isEqualTo("span-0001");
    }

    @Test
    void mcpWarmModelCacheVerifiesLocalModelArtifacts() throws Exception {
        Path cache = tempDir.resolve("models");
        Files.createDirectories(cache);
        Path model = cache.resolve("layout-v1.bin");
        Files.writeString(model, "local model bytes");
        String sha = "sha256:" + sha256Hex(model);
        var cli = cli("""
                {"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"doctruth.warm_model_cache","arguments":{"cacheDir":"%s","models":[{"name":"layout","version":"v1","sha256":"%s","sizeBytes":17,"required":true}]}}}
                """.formatted(jsonEscape(cache.toString()), sha));

        int code = cli.run(new String[] {"mcp"});

        assertThat(code).isZero();
        JsonNode structured = readJson(cli.out().strip()).path("result").path("structuredContent");
        assertThat(structured.path("cacheDir").asText()).isEqualTo(cache.toString());
        assertThat(structured.path("allReady").asBoolean()).isTrue();
        assertThat(structured.path("networkAccessRequired").asBoolean()).isFalse();
        assertThat(structured.path("artifacts").get(0).path("status").asText()).isEqualTo("READY");
        assertThat(structured.path("artifacts").get(0).path("actualSha256").asText())
                .isEqualTo(sha);
    }

    private TestCli cli(String stdin) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var cli = new DocTruthCli(
                Map.of(),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                spec -> "{}",
                Providers::create);
        return new TestCli(cli, out, err);
    }

    private Path samplePdf() throws IOException {
        Path path = tempDir.resolve("mcp-evidence.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 720);
                cs.showText("MCP Evidence Contract");
                cs.newLineAtOffset(0, -18);
                cs.showText("Every answer needs a replayable source span.");
                cs.endText();
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path tablePdf() throws IOException {
        Path path = tempDir.resolve("mcp-table.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
                float left = 80f;
                float top = 700f;
                float cellWidth = 120f;
                float cellHeight = 36f;
                for (int col = 0; col <= 2; col++) {
                    float x = left + col * cellWidth;
                    cs.moveTo(x, top);
                    cs.lineTo(x, top - 2 * cellHeight);
                }
                for (int row = 0; row <= 2; row++) {
                    float y = top - row * cellHeight;
                    cs.moveTo(left, y);
                    cs.lineTo(left + 2 * cellWidth, y);
                }
                cs.stroke();
                writeCell(cs, "Name", left + 12, top - 24);
                writeCell(cs, "Score", left + cellWidth + 12, top - 24);
                writeCell(cs, "Alex", left + 12, top - cellHeight - 24);
                writeCell(cs, "98", left + cellWidth + 12, top - cellHeight - 24);
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path fakeMcpRuntime() throws IOException {
        Path runtime = tempDir.resolve("fake-mcp-runtime");
        Files.writeString(runtime, """
                #!/usr/bin/env sh
                cat >/dev/null
                cat <<'JSON'
                {"docId":"sha256:mcp-runtime","source":{"sourceFilename":"runtime.pdf","sourceHash":"sha256:mcp-runtime","metadata":{"sourceFilename":"runtime.pdf","pageCount":1}},"body":{"pages":[{"pageNumber":1,"width":1000,"height":1000,"textLayerAvailable":true,"imageHash":"sha256:image"}],"units":[{"unitId":"unit-0001","kind":"LINE_SPAN","page":1,"text":"MCP Rust Runtime Evidence Contract","evidenceSpanIds":["span-0001"],"location":{"page":1,"readingOrder":1,"boundingBox":{"x0":10,"y0":20,"x1":300,"y1":80}},"sourceObjectId":"runtime-line-1","confidence":{"score":1.0,"rationale":"rust runtime"},"warnings":[]},{"unitId":"unit-0002","kind":"TABLE_CELL","page":1,"text":"Name","evidenceSpanIds":["span-0002"],"location":{"page":1,"readingOrder":2,"boundingBox":{"x0":100,"y0":100,"x1":220,"y1":150}},"sourceObjectId":"runtime-table-1","confidence":{"score":1.0,"rationale":"rust runtime"},"warnings":[]},{"unitId":"unit-0003","kind":"TABLE_CELL","page":1,"text":"Score","evidenceSpanIds":["span-0003"],"location":{"page":1,"readingOrder":3,"boundingBox":{"x0":220,"y0":100,"x1":340,"y1":150}},"sourceObjectId":"runtime-table-1","confidence":{"score":1.0,"rationale":"rust runtime"},"warnings":[]},{"unitId":"unit-0004","kind":"TABLE_CELL","page":1,"text":"Alex","evidenceSpanIds":["span-0004"],"location":{"page":1,"readingOrder":4,"boundingBox":{"x0":100,"y0":150,"x1":220,"y1":200}},"sourceObjectId":"runtime-table-1","confidence":{"score":1.0,"rationale":"rust runtime"},"warnings":[]},{"unitId":"unit-0005","kind":"TABLE_CELL","page":1,"text":"98","evidenceSpanIds":["span-0005"],"location":{"page":1,"readingOrder":5,"boundingBox":{"x0":220,"y0":150,"x1":340,"y1":200}},"sourceObjectId":"runtime-table-1","confidence":{"score":1.0,"rationale":"rust runtime"},"warnings":[]}],"tables":[{"tableId":"runtime-table-1","pageNumber":1,"boundingBox":{"x0":100,"y0":100,"x1":340,"y1":200},"confidence":{"score":1.0,"rationale":"rust runtime"},"cells":[{"cellId":"cell-1","rowRange":{"start":1,"end":1},"columnRange":{"start":1,"end":1},"boundingBox":{"x0":100,"y0":100,"x1":220,"y1":150},"text":"Name"},{"cellId":"cell-2","rowRange":{"start":1,"end":1},"columnRange":{"start":2,"end":2},"boundingBox":{"x0":220,"y0":100,"x1":340,"y1":150},"text":"Score"},{"cellId":"cell-3","rowRange":{"start":2,"end":2},"columnRange":{"start":1,"end":1},"boundingBox":{"x0":100,"y0":150,"x1":220,"y1":200},"text":"Alex"},{"cellId":"cell-4","rowRange":{"start":2,"end":2},"columnRange":{"start":2,"end":2},"boundingBox":{"x0":220,"y0":150,"x1":340,"y1":200},"text":"98"}]}]},"parserRun":{"parserVersion":"runtime-test","preset":"lite","backend":"sidecar","models":[],"warnings":[]},"auditGradeStatus":"AUDIT_GRADE"}
                JSON
                """, StandardCharsets.UTF_8);
        assertThat(runtime.toFile().setExecutable(true)).isTrue();
        return runtime;
    }

    private static void writeCell(PDPageContentStream cs, String text, float x, float y) throws IOException {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static JsonNode readJson(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (IOException e) {
            throw new AssertionError("invalid JSON line: " + line, e);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sha256Hex(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record TestCli(DocTruthCli delegate, ByteArrayOutputStream outBytes, ByteArrayOutputStream errBytes) {
        int run(String[] args) {
            return delegate.run(args);
        }

        String out() {
            return outBytes.toString(StandardCharsets.UTF_8);
        }

        String err() {
            return errBytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static int withSystemProperty(String key, String value, ThrowingIntSupplier supplier) throws Exception {
        String previous = System.getProperty(key);
        System.setProperty(key, value);
        try {
            return supplier.getAsInt();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingIntSupplier {
        int getAsInt() throws Exception;
    }
}
