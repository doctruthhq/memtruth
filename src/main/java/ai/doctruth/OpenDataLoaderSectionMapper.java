package ai.doctruth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

final class OpenDataLoaderSectionMapper {

    private final OpenDataLoaderPdfGeometry geometry;
    private final Map<Integer, Integer> nextLineByPage = new HashMap<>();

    OpenDataLoaderSectionMapper(OpenDataLoaderPdfGeometry geometry) {
        this.geometry = geometry;
    }

    List<ParsedSection> map(JsonNode kids) {
        if (kids == null || !kids.isArray()) {
            return new ArrayList<>();
        }
        var sections = new ArrayList<ParsedSection>(kids.size());
        for (JsonNode kid : kids) {
            append(kid, sections);
        }
        return sections;
    }

    void append(JsonNode node, List<ParsedSection> sections) {
        String type = node.path("type").asText("");
        switch (type) {
            case "paragraph", "heading", "list item", "line", "text chunk", "text block" ->
                appendText(node, type, sections);
            case "list" -> appendList(node, sections);
            case "table" -> appendTable(node, sections);
            default -> appendNested(node, sections);
        }
    }

    private void appendText(JsonNode node, String type, List<ParsedSection> sections) {
        String text = textFrom(node).trim();
        if (text.isEmpty()) {
            return;
        }
        int page = pageNumber(node);
        sections.add(new TextSection(text, location(page), kind(type), boundingBox(node, page)));
    }

    private void appendList(JsonNode node, List<ParsedSection> sections) {
        String text = textFrom(node.path("list items")).trim();
        if (text.isEmpty()) {
            appendNested(node, sections);
            return;
        }
        int page = pageNumber(node);
        sections.add(new TextSection(text, location(page), BlockKind.LIST, boundingBox(node, page)));
    }

    private void appendTable(JsonNode node, List<ParsedSection> sections) {
        JsonNode tableRows = node.path("rows");
        var rows = new ArrayList<List<String>>(tableRows.isArray() ? tableRows.size() : 0);
        if (tableRows.isArray()) {
            for (JsonNode row : tableRows) {
                rows.add(tableCells(row));
            }
        }
        sections.add(new TableSection(rows, location(pageNumber(node))));
    }

    private List<String> tableCells(JsonNode row) {
        JsonNode cellsNode = row.path("cells");
        var cells = new ArrayList<String>(cellsNode.isArray() ? cellsNode.size() : 0);
        for (JsonNode cell : cellsNode) {
            cells.add(textFrom(cell).trim());
        }
        return cells;
    }

    private void appendNested(JsonNode node, List<ParsedSection> sections) {
        appendArray(node.path("kids"), sections);
        appendArray(node.path("list items"), sections);
    }

    private void appendArray(JsonNode nodes, List<ParsedSection> sections) {
        if (!nodes.isArray()) {
            return;
        }
        for (JsonNode nested : nodes) {
            append(nested, sections);
        }
    }

    private String textFrom(JsonNode node) {
        var text = new StringBuilder();
        appendTextFrom(node, text);
        return text.toString();
    }

    private boolean appendTextFrom(JsonNode node, StringBuilder text) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isArray()) {
            return appendTextFromArray(node, text);
        }
        String direct = node.path("content").asText("");
        if (!direct.isBlank()) {
            appendTextSegment(text, direct.trim());
            return true;
        }
        boolean appended = appendTextFromArray(node.path("kids"), text);
        return appendTextFromArray(node.path("list items"), text) || appended;
    }

    private boolean appendTextFromArray(JsonNode nodes, StringBuilder text) {
        if (!nodes.isArray()) {
            return false;
        }
        boolean appended = false;
        for (JsonNode child : nodes) {
            appended = appendTextFrom(child, text) || appended;
        }
        return appended;
    }

    private static void appendTextSegment(StringBuilder text, String segment) {
        if (segment.isEmpty()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(segment);
    }

    private SourceLocation location(int page) {
        int line = nextLineByPage.merge(page, 1, Integer::sum);
        return new SourceLocation(page, page, line, line, 0);
    }

    private Optional<BoundingBox> boundingBox(JsonNode node, int page) {
        JsonNode box = node.path("bounding box");
        if (!box.isArray() || box.size() != 4) {
            return Optional.empty();
        }
        Optional<OpenDataLoaderPdfGeometry.PageGeometry> pageGeometry = geometry.page(page);
        if (pageGeometry.isEmpty()) {
            return Optional.empty();
        }
        return normalizedBoundingBox(box, pageGeometry.get());
    }

    private static Optional<BoundingBox> normalizedBoundingBox(
            JsonNode box, OpenDataLoaderPdfGeometry.PageGeometry geometry) {
        double left = box.get(0).asDouble(Double.NaN);
        double bottom = box.get(1).asDouble(Double.NaN);
        double right = box.get(2).asDouble(Double.NaN);
        double top = box.get(3).asDouble(Double.NaN);
        if (!validRawBox(left, bottom, right, top, geometry)) {
            return Optional.empty();
        }
        double x0 = clamp(left / geometry.width() * 1000.0);
        double x1 = clamp(right / geometry.width() * 1000.0);
        double y0 = clamp((geometry.height() - top) / geometry.height() * 1000.0);
        double y1 = clamp((geometry.height() - bottom) / geometry.height() * 1000.0);
        if (x1 <= x0 || y1 <= y0) {
            return Optional.empty();
        }
        return Optional.of(new BoundingBox(x0, y0, x1, y1));
    }

    private static boolean validRawBox(
            double left, double bottom, double right, double top, OpenDataLoaderPdfGeometry.PageGeometry geometry) {
        return Double.isFinite(left)
                && Double.isFinite(bottom)
                && Double.isFinite(right)
                && Double.isFinite(top)
                && geometry.width() > 0
                && geometry.height() > 0
                && right > left
                && top > bottom;
    }

    private static int pageNumber(JsonNode node) {
        return Math.max(1, node.path("page number").asInt(1));
    }

    private static BlockKind kind(String type) {
        return switch (type) {
            case "heading" -> BlockKind.HEADING;
            case "list item" -> BlockKind.LIST;
            case "paragraph", "text block" -> BlockKind.BODY;
            default -> BlockKind.OTHER;
        };
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return Double.NaN;
        }
        return Math.max(0.0, Math.min(1000.0, value));
    }
}
