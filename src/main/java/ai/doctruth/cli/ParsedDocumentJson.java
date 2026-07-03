package ai.doctruth.cli;

import ai.doctruth.BoundingBox;
import ai.doctruth.FigureSection;
import ai.doctruth.ParsedDocument;
import ai.doctruth.SourceLocation;
import ai.doctruth.TableSection;
import ai.doctruth.TextSection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

final class ParsedDocumentJson {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private ParsedDocumentJson() {
        throw new AssertionError("no instances");
    }

    static String toJson(ParsedDocument doc) throws CliException {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toNode(doc));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new CliException("failed to serialize parsed document", e);
        }
    }

    private static ObjectNode toNode(ParsedDocument doc) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("docId", doc.docId());
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("sourceFilename", doc.metadata().sourceFilename());
        metadata.put("pageCount", doc.metadata().pageCount());
        doc.metadata().sourcePublishedAt().ifPresent(t -> metadata.put("sourcePublishedAt", t.toString()));
        root.set("metadata", metadata);
        ArrayNode sections = MAPPER.createArrayNode();
        doc.sections().forEach(section -> {
            switch (section) {
                case TextSection text -> sections.add(textNode(text));
                case TableSection table -> sections.add(tableNode(table));
                case FigureSection figure -> sections.add(figureNode(figure));
            }
        });
        root.set("sections", sections);
        return root;
    }

    private static ObjectNode textNode(TextSection section) {
        ObjectNode node = base("text", section.location());
        node.put("kind", section.kind().name());
        node.put("text", section.text());
        section.boundingBox().ifPresent(box -> node.set("boundingBox", bbox(box)));
        return node;
    }

    private static ObjectNode tableNode(TableSection section) {
        ObjectNode node = base("table", section.location());
        node.set("rows", MAPPER.valueToTree(section.rows()));
        return node;
    }

    private static ObjectNode figureNode(FigureSection section) {
        ObjectNode node = base("figure", section.location());
        node.put("caption", section.caption());
        section.boundingBox().ifPresent(box -> node.set("boundingBox", bbox(box)));
        return node;
    }

    private static ObjectNode base(String type, SourceLocation location) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        node.set("location", MAPPER.valueToTree(location));
        return node;
    }

    private static ObjectNode bbox(BoundingBox box) {
        return MAPPER.valueToTree(box);
    }
}
