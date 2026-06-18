package ai.doctruth.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.doctruth.BlockKind;
import ai.doctruth.AuditGradeStatus;
import ai.doctruth.BoundingBox;
import ai.doctruth.Confidence;
import ai.doctruth.DocumentMetadata;
import ai.doctruth.ParsedDocument;
import ai.doctruth.ParserRun;
import ai.doctruth.ParserWarning;
import ai.doctruth.ParserWarningSeverity;
import ai.doctruth.SourceLocation;
import ai.doctruth.TextSection;
import ai.doctruth.TrustCellRange;
import ai.doctruth.TrustDocument;
import ai.doctruth.TrustDocumentBody;
import ai.doctruth.TrustDocumentSource;
import ai.doctruth.TrustPage;
import ai.doctruth.TrustTable;
import ai.doctruth.TrustTableCell;
import ai.doctruth.TrustUnit;
import ai.doctruth.TrustUnitContent;
import ai.doctruth.TrustUnitEvidence;
import ai.doctruth.TrustUnitKind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class BenchmarkOracleCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENGINE = "opendataloader-hybrid";
    private static final String ORACLE_COMMAND_ENV = "DOCTRUTH_OPENDATALOADER_HYBRID_ORACLE_COMMAND";

    private final CliContext context;

    BenchmarkOracleCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = Options.parse(args);
        if (!ENGINE.equals(options.engine())) {
            throw new UsageException("unknown benchmark oracle engine: " + options.engine());
        }
        var command = context.env().get(ORACLE_COMMAND_ENV);
        if (command == null || command.isBlank()) {
            throw new CliException(unavailableMessage());
        }
        var document = runOpenDataLoaderHybridOracle(command, options.document());
        switch (options.format()) {
            case JSON -> context.out().print(document.toJsonFull());
            case CONTENT_BLOCKS -> TrustDocumentCliWriters.writeToPrintStream(
                    context.out(), document::writeContentBlocks);
            case PARSE_TRACE -> TrustDocumentCliWriters.writeToPrintStream(
                    context.out(), document::writeParseTrace);
            case SUMMARY -> {
                context.out().println("benchmark oracle: " + ENGINE);
                context.out().println("parser backend: " + document.parserRun().backend());
                context.out().println("audit grade: " + document.auditGradeStatus());
            }
        }
    }

    private TrustDocument runOpenDataLoaderHybridOracle(String command, Path document) throws CliException {
        var output = runProcess(command, document);
        var root = readOracleJson(output);
        String sourceHash = ParseCommand.sourceHashForFile(document);
        if (hasStructuredBlocks(root)) {
            return structuredTrustDocument(document, sourceHash, root);
        }
        var parsed = markdownParsedDocument(document, sourceHash, text(root, "markdown"));
        var parserRun = parserRun(root);
        return TrustDocument.fromParsed(parsed, sourceHash, parserRun).withEvaluatedAuditGrade();
    }

    private String runProcess(String command, Path document) throws CliException {
        try {
            var argv = commandTokens(command);
            argv.add(document.toString());
            var process = new ProcessBuilder(argv).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new CliException("opendataloader-hybrid oracle exited " + exit + ": " + stderr.strip());
            }
            return stdout;
        } catch (IOException e) {
            throw new CliException("opendataloader-hybrid oracle unavailable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("opendataloader-hybrid oracle interrupted", e);
        }
    }

    private static List<String> commandTokens(String command) throws CliException {
        var tokens = new ArrayList<String>();
        var current = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < command.length(); index++) {
            char value = command.charAt(index);
            if (quote == 0 && Character.isWhitespace(value)) {
                addToken(tokens, current);
            } else if ((value == '"' || value == '\'') && (quote == 0 || quote == value)) {
                quote = quote == 0 ? value : 0;
            } else {
                current.append(value);
            }
        }
        if (quote != 0) {
            throw new CliException("opendataloader-hybrid oracle command has unterminated quote");
        }
        addToken(tokens, current);
        if (tokens.isEmpty()) {
            throw new CliException(unavailableMessage());
        }
        return tokens;
    }

    private static void addToken(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private static JsonNode readOracleJson(String output) throws CliException {
        try {
            return MAPPER.readTree(output);
        } catch (IOException e) {
            throw new CliException("opendataloader-hybrid oracle returned invalid JSON: " + e.getMessage(), e);
        }
    }

    private static ParsedDocument markdownParsedDocument(Path document, String sourceHash, String markdown) {
        var sections = new ArrayList<ai.doctruth.ParsedSection>();
        var lines = markdown.split("\\R");
        int charOffset = 0;
        int lineNumber = 1;
        for (String line : lines) {
            String text = cleanMarkdownLine(line);
            if (!text.isBlank()) {
                sections.add(new TextSection(
                        text,
                        new SourceLocation(1, 1, lineNumber, lineNumber, charOffset),
                        blockKind(line)));
            }
            charOffset += line.length() + 1;
            lineNumber++;
        }
        var metadata = new DocumentMetadata(document.getFileName().toString(), 1, Optional.<Instant>empty());
        return new ParsedDocument(sourceHash, sections, metadata);
    }

    private static TrustDocument structuredTrustDocument(Path document, String sourceHash, JsonNode root) {
        var blocks = root.path("blocks");
        var units = new ArrayList<TrustUnit>();
        var tables = new ArrayList<TrustTable>();
        var contentBlocks = MAPPER.createArrayNode();
        var traceBlocks = MAPPER.createArrayNode();
        int unitIndex = 1;
        int tableIndex = 1;
        int maxPage = 1;
        for (int index = 0; index < blocks.size(); index++) {
            JsonNode block = blocks.get(index);
            int page = page(block);
            maxPage = Math.max(maxPage, page);
            int readingOrder = readingOrder(block, index + 1);
            String blockId = blockId(block, readingOrder);
            switch (blockType(block)) {
                case "table" -> {
                    var adapted = addStructuredTable(block, blockId, page, readingOrder, tableIndex++, unitIndex);
                    units.addAll(adapted.units());
                    tables.add(adapted.table());
                    unitIndex += adapted.units().size();
                    contentBlocks.add(contentBlock(block, blockId, "table", adapted.units(), adapted.table(), readingOrder));
                }
                case "list" -> {
                    var adapted = addStructuredList(block, blockId, page, readingOrder, unitIndex);
                    units.addAll(adapted.units());
                    unitIndex += adapted.units().size();
                    contentBlocks.add(contentBlock(block, blockId, "list", adapted.units(), null, readingOrder));
                }
                case "heading" -> {
                    var unit = textUnit(unitIndex++, blockId, text(block), page, readingOrder, bbox(block));
                    units.add(unit);
                    contentBlocks.add(contentBlock(block, blockId, "heading", List.of(unit), null, readingOrder));
                }
                default -> {
                    if (!text(block).isBlank()) {
                        var unit = textUnit(unitIndex++, blockId, text(block), page, readingOrder, bbox(block));
                        units.add(unit);
                        contentBlocks.add(contentBlock(block, blockId, "text", List.of(unit), null, readingOrder));
                    }
                }
            }
            traceBlocks.add(traceBlock(contentBlocks.get(contentBlocks.size() - 1)));
        }
        var metadata = new DocumentMetadata(document.getFileName().toString(), maxPage, Optional.<Instant>empty());
        var body = new TrustDocumentBody(pages(maxPage), units, tables);
        var parserRun = structuredParserRun(root);
        var source = new TrustDocumentSource(document.getFileName().toString(), sourceHash, metadata);
        var doc = new TrustDocument(sourceHash, source, body, parserRun, AuditGradeStatus.UNKNOWN)
                .withEvaluatedAuditGrade();
        return doc.withLayeredOutputs(contentBlocks, parseTrace(parserRun, maxPage, traceBlocks));
    }

    private static StructuredTable addStructuredTable(
            JsonNode block, String blockId, int page, int readingOrder, int tableIndex, int firstUnitIndex) {
        var cells = new ArrayList<TrustTableCell>();
        var units = new ArrayList<TrustUnit>();
        var rows = block.path("rows");
        int unitIndex = firstUnitIndex;
        for (int row = 0; row < rows.size(); row++) {
            JsonNode rowNode = rows.get(row);
            for (int column = 0; column < rowNode.size(); column++) {
                String text = cellText(rowNode.get(column));
                String cellId = "cell-%04d-%04d-%04d".formatted(tableIndex, row, column);
                cells.add(new TrustTableCell(
                        cellId,
                        new TrustCellRange(row, row),
                        new TrustCellRange(column, column),
                        Optional.empty(),
                        text));
                if (!text.isBlank()) {
                    units.add(tableCellUnit(unitIndex++, cellId, text, page, readingOrder));
                }
            }
        }
        var table = new TrustTable(
                "table-%04d".formatted(tableIndex),
                page,
                bbox(block),
                new Confidence(1.0, "opendataloader structured table"),
                cells);
        return new StructuredTable(table, units);
    }

    private static StructuredUnits addStructuredList(
            JsonNode block, String blockId, int page, int readingOrder, int firstUnitIndex) {
        var units = new ArrayList<TrustUnit>();
        int unitIndex = firstUnitIndex;
        for (JsonNode item : block.path("items")) {
            String text = item.isTextual() ? item.asText() : item.path("text").asText();
            if (!text.isBlank()) {
                units.add(textUnit(unitIndex++, blockId, text, page, readingOrder, bbox(block)));
            }
        }
        return new StructuredUnits(units);
    }

    private static TrustUnit textUnit(
            int unitIndex, String sourceObjectId, String text, int page, int readingOrder, Optional<BoundingBox> bbox) {
        return new TrustUnit(
                "unit-%04d".formatted(unitIndex),
                TrustUnitKind.TEXT_BLOCK,
                new ai.doctruth.TrustUnitLocation(page, bbox, readingOrder),
                new TrustUnitContent(text, sourceObjectId),
                evidence(unitIndex));
    }

    private static TrustUnit tableCellUnit(int unitIndex, String cellId, String text, int page, int readingOrder) {
        return new TrustUnit(
                "unit-%04d".formatted(unitIndex),
                TrustUnitKind.TABLE_CELL,
                new ai.doctruth.TrustUnitLocation(page, Optional.empty(), readingOrder),
                new TrustUnitContent(text, cellId),
                evidence(unitIndex));
    }

    private static TrustUnitEvidence evidence(int unitIndex) {
        return new TrustUnitEvidence(
                List.of("span-%04d".formatted(unitIndex)),
                new Confidence(1.0, "opendataloader structured block"),
                List.of());
    }

    private static ObjectNode contentBlock(
            JsonNode source, String blockId, String type, List<TrustUnit> units, TrustTable table, int readingOrder) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("blockId", blockId);
        node.put("type", type);
        node.put("page", page(source));
        node.put("readingOrder", readingOrder);
        bbox(source).ifPresent(box -> node.set("bbox", MAPPER.valueToTree(box)));
        if (source.has("textLevel")) {
            node.put("textLevel", source.path("textLevel").asInt());
        }
        if (!text(source).isBlank()) {
            node.put("text", text(source));
        }
        if ("list".equals(type)) {
            node.set("items", listItems(source, units));
        }
        if (table != null) {
            node.set("rows", tableRows(table));
        }
        node.set("sourceUnitIds", MAPPER.valueToTree(units.stream().map(TrustUnit::unitId).toList()));
        node.set("evidenceSpanIds", MAPPER.valueToTree(
                units.stream().flatMap(unit -> unit.evidence().evidenceSpanIds().stream()).toList()));
        node.set("warnings", MAPPER.createArrayNode());
        return node;
    }

    private static ArrayNode listItems(JsonNode source, List<TrustUnit> units) {
        ArrayNode items = MAPPER.createArrayNode();
        for (int i = 0; i < source.path("items").size(); i++) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("text", units.get(i).content().text());
            item.put("sourceUnitId", units.get(i).unitId());
            items.add(item);
        }
        return items;
    }

    private static ArrayNode tableRows(TrustTable table) {
        int maxRow = table.cells().stream().mapToInt(cell -> cell.rowRange().end()).max().orElse(-1);
        int maxColumn = table.cells().stream().mapToInt(cell -> cell.columnRange().end()).max().orElse(-1);
        ArrayNode rows = MAPPER.createArrayNode();
        for (int row = 0; row <= maxRow; row++) {
            ArrayNode cells = MAPPER.createArrayNode();
            for (int column = 0; column <= maxColumn; column++) {
                ObjectNode cell = MAPPER.createObjectNode();
                cell.put("text", tableCellText(table, row, column));
                cells.add(cell);
            }
            rows.add(cells);
        }
        return rows;
    }

    private static String tableCellText(TrustTable table, int row, int column) {
        return table.cells().stream()
                .filter(cell -> cell.rowRange().start() <= row && row <= cell.rowRange().end())
                .filter(cell -> cell.columnRange().start() <= column && column <= cell.columnRange().end())
                .findFirst()
                .map(TrustTableCell::text)
                .orElse("");
    }

    private static ObjectNode traceBlock(JsonNode block) {
        ObjectNode trace = MAPPER.createObjectNode();
        trace.put("blockId", block.path("blockId").asText());
        trace.put("type", block.path("type").asText());
        trace.put("readingOrder", block.path("readingOrder").asInt());
        trace.set("sourceUnitIds", block.path("sourceUnitIds").deepCopy());
        return trace;
    }

    private static ObjectNode parseTrace(ParserRun parserRun, int maxPage, ArrayNode readingBlocks) {
        ObjectNode trace = MAPPER.createObjectNode();
        trace.put("traceId", "trace-opendataloader-hybrid-oracle");
        trace.put("parserRunId", parserRun.parserRunId());
        ArrayNode pages = MAPPER.createArrayNode();
        for (int page = 1; page <= maxPage; page++) {
            ObjectNode pageNode = MAPPER.createObjectNode();
            pageNode.put("pageIndex", page - 1);
            pageNode.put("pageNumber", page);
            pageNode.set("readingBlocks", readingBlocks.deepCopy());
            pages.add(pageNode);
        }
        trace.set("pages", pages);
        trace.set("warnings", MAPPER.valueToTree(parserRun.warnings()));
        return trace;
    }

    private static List<TrustPage> pages(int maxPage) {
        var pages = new ArrayList<TrustPage>();
        for (int page = 1; page <= maxPage; page++) {
            pages.add(new TrustPage(page, 1000, 1000, true, ""));
        }
        return pages;
    }

    private static ParserRun parserRun(JsonNode root) {
        return new ParserRun(
                "parser-run-opendataloader-hybrid-oracle",
                "opendataloader-hybrid-oracle",
                "benchmark-oracle",
                "opendataloader-hybrid-oracle",
                List.of(),
                List.of(new ParserWarning(
                        "opendataloader_markdown_only_source_mapping",
                        ParserWarningSeverity.SEVERE,
                        "OpenDataLoader hybrid oracle returned Markdown-level mapping only; sourceRefs are coarse.")),
                externalBackend(root.path("externalBackend")),
                optionalLong(root, "elapsedMs"));
    }

    private static ParserRun structuredParserRun(JsonNode root) {
        return new ParserRun(
                "parser-run-opendataloader-hybrid-oracle",
                "opendataloader-hybrid-oracle",
                "benchmark-oracle",
                "opendataloader-hybrid-oracle",
                List.of(),
                List.of(new ParserWarning(
                        "opendataloader_structured_source_mapping",
                        ParserWarningSeverity.INFO,
                        "OpenDataLoader hybrid oracle returned structured blocks; sourceRefs are normalized from block ids.")),
                externalBackend(root.path("externalBackend")),
                optionalLong(root, "elapsedMs"));
    }

    private static Map<String, String> externalBackend(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        var values = new LinkedHashMap<String, String>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return Map.copyOf(values);
    }

    private static Long optionalLong(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    private static String cleanMarkdownLine(String line) {
        return line.replaceFirst("^#{1,6}\\s+", "")
                .replaceFirst("^[-*+]\\s+", "")
                .strip();
    }

    private static BlockKind blockKind(String line) {
        String stripped = line.stripLeading();
        if (stripped.startsWith("#")) {
            return BlockKind.HEADING;
        }
        if (stripped.matches("^[-*+]\\s+.*")) {
            return BlockKind.LIST;
        }
        return BlockKind.BODY;
    }

    private static boolean hasStructuredBlocks(JsonNode root) {
        return root.path("blocks").isArray() && !root.path("blocks").isEmpty();
    }

    private static String blockType(JsonNode block) {
        String type = block.path("type").asText("text").toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case "heading", "title" -> "heading";
            case "list" -> "list";
            case "table" -> "table";
            default -> "text";
        };
    }

    private static String blockId(JsonNode block, int readingOrder) {
        String id = block.path("blockId").asText();
        if (id.isBlank()) {
            id = block.path("id").asText();
        }
        return id.isBlank() ? "opendataloader-block-%04d".formatted(readingOrder) : id;
    }

    private static int page(JsonNode block) {
        int page = block.path("page").asInt(block.path("page_idx").asInt(0) + 1);
        return Math.max(1, page);
    }

    private static int readingOrder(JsonNode block, int fallback) {
        return block.path("readingOrder").asInt(block.path("index").asInt(fallback));
    }

    private static String text(JsonNode block) {
        return block.path("text").asText(block.path("content").asText("")).strip();
    }

    private static String cellText(JsonNode cell) {
        return cell.isTextual() ? cell.asText() : cell.path("text").asText("").strip();
    }

    private static Optional<BoundingBox> bbox(JsonNode block) {
        JsonNode box = block.path("bbox");
        if (!box.isArray() || box.size() != 4) {
            return Optional.empty();
        }
        return Optional.of(new BoundingBox(box.get(0).asDouble(), box.get(1).asDouble(), box.get(2).asDouble(), box.get(3).asDouble()));
    }

    private static String text(JsonNode root, String field) throws CliException {
        String value = root.path(field).asText();
        if (value.isBlank()) {
            throw new CliException("opendataloader-hybrid oracle JSON missing field: " + field);
        }
        return value;
    }

    private static String unavailableMessage() {
        return "opendataloader-hybrid oracle unavailable: set "
                + ORACLE_COMMAND_ENV
                + " to the benchmark-only OpenDataLoader hybrid runner. Run doctruth doctor for setup guidance.";
    }

    private record StructuredUnits(List<TrustUnit> units) {}

    private record StructuredTable(TrustTable table, List<TrustUnit> units) {}

    private enum OutputFormat {
        SUMMARY,
        JSON,
        CONTENT_BLOCKS,
        PARSE_TRACE;

        static OutputFormat from(String value) {
            return switch (value) {
                case "json" -> JSON;
                case "content_blocks", "content-blocks" -> CONTENT_BLOCKS;
                case "parse_trace", "parse-trace" -> PARSE_TRACE;
                default -> throw new UsageException("unknown benchmark-oracle format: " + value);
            };
        }
    }

    private record Options(String engine, Path document, OutputFormat format) {
        static Options parse(String[] args) {
            String engine = null;
            Path document = null;
            OutputFormat format = OutputFormat.SUMMARY;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--engine" -> {
                        if (++i >= args.length) {
                            throw new UsageException("--engine requires a value");
                        }
                        engine = args[i];
                    }
                    case "--json" -> format = OutputFormat.JSON;
                    case "--format" -> {
                        if (++i >= args.length) {
                            throw new UsageException("--format requires a value");
                        }
                        format = OutputFormat.from(args[i]);
                    }
                    default -> {
                        if (args[i].startsWith("-")) {
                            throw new UsageException("unknown benchmark-oracle option: " + args[i]);
                        }
                        if (document != null) {
                            throw new UsageException("benchmark-oracle accepts one document");
                        }
                        document = Path.of(args[i]);
                    }
                }
            }
            if (engine == null) {
                throw new UsageException("benchmark-oracle requires --engine");
            }
            if (document == null) {
                throw new UsageException("benchmark-oracle requires a document");
            }
            return new Options(engine, document, format);
        }
    }
}
