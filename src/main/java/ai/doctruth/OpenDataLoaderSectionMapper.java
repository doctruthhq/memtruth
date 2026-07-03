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
        var sections = new ArrayList<ParsedSection>();
        if (kids == null || !kids.isArray()) {
            return sections;
        }
        for (JsonNode kid : kids) {
            append(kid, sections);
        }
        return sections;
    }

    private void append(JsonNode node, List<ParsedSection> sections) {
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
        var rows = new ArrayList<List<String>>();
        JsonNode tableRows = node.path("rows");
        if (tableRows.isArray()) {
            for (JsonNode row : tableRows) {
                rows.add(tableCells(row));
            }
        }
        sections.add(new TableSection(rows, location(pageNumber(node))));
    }

    private List<String> tableCells(JsonNode row) {
        var cells = new ArrayList<String>();
        for (JsonNode cell : row.path("cells")) {
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
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            return textFromArray(node);
        }
        String direct = node.path("content").asText("");
        if (!direct.isBlank()) {
            return direct;
        }
        var parts = new ArrayList<String>();
        collectText(node.path("kids"), parts);
        collectText(node.path("list items"), parts);
        return String.join("\n", parts);
    }

    private String textFromArray(JsonNode nodes) {
        var parts = new ArrayList<String>();
        for (JsonNode child : nodes) {
            String childText = textFrom(child).trim();
            if (!childText.isEmpty()) {
                parts.add(childText);
            }
        }
        return String.join("\n", parts);
    }

    private void collectText(JsonNode nodes, List<String> parts) {
        if (!nodes.isArray()) {
            return;
        }
        for (JsonNode child : nodes) {
            String childText = textFrom(child).trim();
            if (!childText.isEmpty()) {
                parts.add(childText);
            }
        }
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
