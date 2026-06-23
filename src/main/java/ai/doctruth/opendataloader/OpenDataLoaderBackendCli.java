package ai.doctruth.opendataloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

import ai.doctruth.BoundingBox;
import ai.doctruth.ParseException;
import ai.doctruth.ParserPreset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Stdio JSONL runner for a warm OpenDataLoader-compatible Java backend process. */
public final class OpenDataLoaderBackendCli {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenDataLoaderBackendCli() {
        throw new AssertionError("no instances");
    }

    public static int run(InputStream in, PrintStream out) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        var backend = new OpenDataLoaderJavaBackend();
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    out.println(handleLine(backend, line));
                    out.flush();
                }
            }
            return 0;
        } catch (IOException e) {
            out.println(error("STDIO_READ_FAILED", e.getMessage()));
            return 1;
        }
    }

    private static String handleLine(OpenDataLoaderJavaBackend backend, String line) {
        try {
            var request = requestFrom(MAPPER.readTree(line));
            return responseJson(backend.parse(request)).toString();
        } catch (ParseException e) {
            return error(e.errorCode(), e.getMessage()).toString();
        } catch (RuntimeException | IOException e) {
            return error("BACKEND_REQUEST_FAILED", e.getMessage()).toString();
        }
    }

    private static OpenDataLoaderBackendRequest requestFrom(JsonNode root) {
        String document = requiredText(root, "document");
        String preset = optionalText(root, "preset", ParserPreset.LITE.id());
        return new OpenDataLoaderBackendRequest(Path.of(document), ParserPreset.fromId(preset));
    }

    private static String requiredText(JsonNode root, String field) {
        String value = root.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String optionalText(JsonNode root, String field, String defaultValue) {
        String value = root.path(field).asText(defaultValue);
        return value.isBlank() ? defaultValue : value;
    }

    private static ObjectNode responseJson(OpenDataLoaderBackendResponse response) throws IOException {
        var root = MAPPER.createObjectNode();
        root.put("ok", true);
        root.put("backend", response.backend());
        root.put("schemaVersion", response.schemaVersion());
        root.put("markdown", response.markdown());
        root.set("blocks", blocksJson(response.blocks()));
        root.set("tables", tablesJson(response.tables()));
        root.set("headings", blocksJson(response.headings()));
        root.set("sourceMap", sourceMapJson(response.sourceMap()));
        root.set("warnings", MAPPER.valueToTree(response.warnings()));
        root.set("metrics", MAPPER.valueToTree(response.metrics()));
        root.set("trustDocument", MAPPER.readTree(response.trustDocument().toJsonFull()));
        return root;
    }

    private static ArrayNode blocksJson(Iterable<OpenDataLoaderBlock> blocks) {
        var array = MAPPER.createArrayNode();
        for (var block : blocks) {
            var node = MAPPER.createObjectNode();
            node.put("id", block.id());
            node.put("kind", block.kind());
            node.put("pageIndex", block.pageIndex());
            block.bbox().ifPresent(box -> node.set("bbox", bboxJson(box)));
            node.put("readingOrder", block.readingOrder());
            node.put("text", block.text());
            node.put("sourceUnitId", block.sourceUnitId());
            array.add(node);
        }
        return array;
    }

    private static ArrayNode tablesJson(Iterable<OpenDataLoaderTable> tables) {
        var array = MAPPER.createArrayNode();
        for (var table : tables) {
            var node = MAPPER.createObjectNode();
            node.put("id", table.id());
            node.put("pageIndex", table.pageIndex());
            table.bbox().ifPresent(box -> node.set("bbox", bboxJson(box)));
            node.set("cells", tableCellsJson(table.cells()));
            array.add(node);
        }
        return array;
    }

    private static ArrayNode tableCellsJson(Iterable<OpenDataLoaderTableCell> cells) {
        var array = MAPPER.createArrayNode();
        for (var cell : cells) {
            var node = MAPPER.createObjectNode();
            node.put("id", cell.id());
            node.put("rowStart", cell.rowStart());
            node.put("rowEnd", cell.rowEnd());
            node.put("columnStart", cell.columnStart());
            node.put("columnEnd", cell.columnEnd());
            cell.bbox().ifPresent(box -> node.set("bbox", bboxJson(box)));
            node.put("text", cell.text());
            array.add(node);
        }
        return array;
    }

    private static ArrayNode sourceMapJson(Iterable<OpenDataLoaderSourceRef> refs) {
        var array = MAPPER.createArrayNode();
        for (var ref : refs) {
            var node = MAPPER.createObjectNode();
            node.put("unitId", ref.unitId());
            node.put("pageIndex", ref.pageIndex());
            ref.bbox().ifPresent(box -> node.set("bbox", bboxJson(box)));
            node.put("text", ref.text());
            array.add(node);
        }
        return array;
    }

    private static ArrayNode bboxJson(BoundingBox box) {
        var array = MAPPER.createArrayNode();
        array.add(box.x0());
        array.add(box.y0());
        array.add(box.x1());
        array.add(box.y1());
        return array;
    }

    private static ObjectNode error(String code, String message) {
        var root = MAPPER.createObjectNode();
        root.put("ok", false);
        root.put("errorCode", code == null || code.isBlank() ? "BACKEND_REQUEST_FAILED" : code);
        root.put("message", message == null ? "" : message);
        return root;
    }
}
