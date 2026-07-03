package ai.doctruth.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

import ai.doctruth.ParseException;
import ai.doctruth.ParserPreset;
import ai.doctruth.TrustDocument;
import ai.doctruth.TrustDocumentParser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class McpCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final CliContext context;

    McpCommand(CliContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    void run(String[] args) throws CliException {
        if (args.length != 1) {
            throw new UsageException("usage: doctruth mcp");
        }
        try (var reader = new BufferedReader(new InputStreamReader(context.in(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                handleLine(line);
            }
        } catch (IOException e) {
            throw new CliException("failed to run MCP stdio server: " + e.getMessage(), e);
        }
    }

    private void handleLine(String line) throws IOException {
        JsonNode request = MAPPER.readTree(line);
        JsonNode id = request.path("id");
        if (id.isMissingNode() || id.isNull()) {
            return;
        }
        String method = request.path("method").asText("");
        try {
            switch (method) {
                case "initialize" -> writeResult(id, initializeResult());
                case "tools/list" -> writeResult(id, McpToolSchemas.toolsListResult());
                case "tools/call" -> writeResult(id, callTool(request.path("params")));
                default -> writeError(id, -32601, "unknown MCP method: " + method);
            }
        } catch (UsageException e) {
            writeError(id, -32602, e.getMessage());
        } catch (ParseException e) {
            writeError(id, -32000, e.errorCode() + ": " + e.getMessage());
        }
    }

    private static ObjectNode initializeResult() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = MAPPER.createObjectNode();
        capabilities.set("tools", MAPPER.createObjectNode());
        result.set("capabilities", capabilities);
        ObjectNode server = MAPPER.createObjectNode();
        server.put("name", "doctruth");
        server.put("version", "0.2.0-alpha");
        result.set("serverInfo", server);
        return result;
    }

    private ObjectNode callTool(JsonNode params) throws ParseException, IOException {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");
        return switch (name) {
            case McpToolSchemas.PARSE_DOCUMENT -> parseDocument(arguments);
            case McpToolSchemas.GET_LAYOUT_REGIONS -> McpToolResults.layoutRegions(parse(arguments));
            case McpToolSchemas.GET_TABLE_CELLS -> McpToolResults.tableCells(parse(arguments));
            case McpToolSchemas.GET_EVIDENCE_SPAN ->
                McpToolResults.evidenceSpan(parse(arguments), requiredText(arguments, "evidenceSpanId"));
            case McpToolSchemas.VERIFY_CITATION ->
                McpToolResults.verifyCitation(
                        parse(arguments), requiredText(arguments, "evidenceSpanId"), requiredText(arguments, "quote"));
            case McpToolSchemas.WARM_MODEL_CACHE -> McpToolResults.warmModelCache(arguments);
            default -> throw new UsageException("unknown MCP tool: " + name);
        };
    }

    private ObjectNode parseDocument(JsonNode arguments) throws ParseException, IOException {
        String path = requiredText(arguments, "path");
        String format = arguments.path("format").asText("compact_llm");
        ParserPreset preset = ParserPreset.fromId(arguments.path("preset").asText("lite"));
        boolean sourceMap = arguments.path("sourceMap").asBoolean(true);
        TrustDocument doc = TrustDocumentParser.parse(Path.of(path), preset);
        return McpToolResults.parseDocument(doc, format, sourceMap);
    }

    private static TrustDocument parse(JsonNode arguments) throws ParseException {
        return TrustDocumentParser.parse(
                Path.of(requiredText(arguments, "path")),
                ParserPreset.fromId(arguments.path("preset").asText("lite")));
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new UsageException("MCP argument is required: " + field);
        }
        return value;
    }

    private void writeResult(JsonNode id, JsonNode result) throws IOException {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        context.out().println(MAPPER.writeValueAsString(response));
    }

    private void writeError(JsonNode id, int code, String message) throws IOException {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = MAPPER.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        context.out().println(MAPPER.writeValueAsString(response));
    }
}
