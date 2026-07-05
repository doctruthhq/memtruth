package ai.doctruth.cli;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import ai.doctruth.BoundingBox;
import ai.doctruth.FigureSection;
import ai.doctruth.ParsedDocument;
import ai.doctruth.ParsedSection;
import ai.doctruth.SourceLocation;
import ai.doctruth.TableSection;
import ai.doctruth.TextSection;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

final class ParsedDocumentJson {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private ParsedDocumentJson() {
        throw new AssertionError("no instances");
    }

    static String toJson(ParsedDocument doc) throws CliException {
        try {
            var out = new StringWriter();
            writeJson(doc, out);
            return out.toString();
        } catch (IOException e) {
            throw new CliException("failed to serialize parsed document", e);
        }
    }

    static void writeJson(ParsedDocument doc, Path output) throws CliException {
        try {
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(output)) {
                writeJson(doc, writer);
            }
        } catch (IOException e) {
            throw new CliException("failed to write parsed document JSON: " + e.getMessage(), e);
        }
    }

    private static void writeJson(ParsedDocument doc, Writer writer) throws IOException {
        try (JsonGenerator json = MAPPER.getFactory().createGenerator(writer).useDefaultPrettyPrinter()) {
            writeDocument(json, doc);
        }
    }

    private static void writeDocument(JsonGenerator json, ParsedDocument doc) throws IOException {
        json.writeStartObject();
        json.writeStringField("docId", doc.docId());
        json.writeObjectFieldStart("metadata");
        json.writeStringField("sourceFilename", doc.metadata().sourceFilename());
        json.writeNumberField("pageCount", doc.metadata().pageCount());
        if (doc.metadata().sourcePublishedAt().isPresent()) {
            json.writeStringField(
                    "sourcePublishedAt",
                    doc.metadata().sourcePublishedAt().get().toString());
        }
        json.writeEndObject();
        json.writeArrayFieldStart("sections");
        for (ParsedSection section : doc.sections()) {
            writeSection(json, section);
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    private static void writeSection(JsonGenerator json, ParsedSection section) throws IOException {
        switch (section) {
            case TextSection text -> writeTextSection(json, text);
            case TableSection table -> writeTableSection(json, table);
            case FigureSection figure -> writeFigureSection(json, figure);
        }
    }

    private static void writeTextSection(JsonGenerator json, TextSection section) throws IOException {
        writeSectionStart(json, "text", section.location());
        json.writeStringField("kind", section.kind().name());
        json.writeStringField("text", section.text());
        if (section.boundingBox().isPresent()) {
            writeBoundingBox(json, section.boundingBox().get());
        }
        json.writeEndObject();
    }

    private static void writeTableSection(JsonGenerator json, TableSection section) throws IOException {
        writeSectionStart(json, "table", section.location());
        json.writeArrayFieldStart("rows");
        for (var row : section.rows()) {
            json.writeStartArray();
            for (String cell : row) {
                json.writeString(cell);
            }
            json.writeEndArray();
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    private static void writeFigureSection(JsonGenerator json, FigureSection section) throws IOException {
        writeSectionStart(json, "figure", section.location());
        json.writeStringField("caption", section.caption());
        json.writeEndObject();
    }

    private static void writeSectionStart(JsonGenerator json, String type, SourceLocation location) throws IOException {
        json.writeStartObject();
        json.writeStringField("type", type);
        writeLocation(json, location);
    }

    private static void writeLocation(JsonGenerator json, SourceLocation location) throws IOException {
        json.writeObjectFieldStart("location");
        json.writeNumberField("pageStart", location.pageStart());
        json.writeNumberField("pageEnd", location.pageEnd());
        json.writeNumberField("lineStart", location.lineStart());
        json.writeNumberField("lineEnd", location.lineEnd());
        json.writeNumberField("charOffset", location.charOffset());
        json.writeEndObject();
    }

    private static void writeBoundingBox(JsonGenerator json, BoundingBox box) throws IOException {
        json.writeObjectFieldStart("boundingBox");
        json.writeNumberField("x0", box.x0());
        json.writeNumberField("y0", box.y0());
        json.writeNumberField("x1", box.x1());
        json.writeNumberField("y1", box.y1());
        json.writeEndObject();
    }
}
