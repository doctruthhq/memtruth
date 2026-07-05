package ai.doctruth;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON renderer for the stable TrustDocument contract.
 *
 * @since 0.2.0
 */
public final class TrustDocumentJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory JSON = MAPPER.getFactory();

    private TrustDocumentJson() {
        throw new AssertionError("no instances");
    }

    public static String toJson(TrustDocument doc) {
        var out = new StringWriter();
        writeJson(doc, out);
        return out.toString();
    }

    public static void writeJson(TrustDocument doc, Writer writer) {
        try {
            try (JsonGenerator json = jsonGenerator(writer)) {
                writeDocument(json, doc);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize TrustDocument", e);
        }
    }

    public static String toJson(ParsedDocument doc, Path source, PdfParserBackend backend) {
        Objects.requireNonNull(backend, "backend");
        return toJson(doc, source, backend.id());
    }

    public static String toJson(ParsedDocument doc, Path source, String backend) {
        var out = new StringWriter();
        writeJson(doc, source, backend, out);
        return out.toString();
    }

    public static void writeJson(ParsedDocument doc, Path source, PdfParserBackend backend, Writer writer) {
        Objects.requireNonNull(backend, "backend");
        writeJson(doc, source, backend.id(), writer);
    }

    public static void writeJson(ParsedDocument doc, Path source, String backend, Writer writer) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(backend, "backend");
        try {
            try (JsonGenerator json = jsonGenerator(writer)) {
                writeParsedDocument(json, doc, source, backend);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize TrustDocument", e);
        }
    }

    public static void writeJson(ParsedDocument doc, Path source, PdfParserBackend backend, Path output) {
        Objects.requireNonNull(backend, "backend");
        writeJson(doc, source, backend.id(), output);
    }

    public static void writeJson(ParsedDocument doc, Path source, String backend, Path output) {
        try {
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(output)) {
                writeJson(doc, source, backend, writer);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to write TrustDocument", e);
        }
    }

    private static JsonGenerator jsonGenerator(Writer writer) throws IOException {
        JsonGenerator json = JSON.createGenerator(writer);
        json.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        return json.useDefaultPrettyPrinter();
    }

    private static void writeDocument(JsonGenerator json, TrustDocument doc) throws IOException {
        json.writeStartObject();
        json.writeStringField("schemaVersion", doc.schemaVersion());
        json.writeStringField("docId", doc.docId());
        writeSource(json, doc.source());
        writeParserRun(json, doc.parserRun().backend());
        json.writeArrayFieldStart("units");
        for (TrustUnit unit : doc.units()) {
            writeUnit(json, unit);
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    private static void writeParsedDocument(JsonGenerator json, ParsedDocument doc, Path source, String backend)
            throws IOException {
        json.writeStartObject();
        json.writeStringField("schemaVersion", TrustDocument.SCHEMA_VERSION);
        json.writeStringField("docId", doc.docId());
        writeSource(
                json,
                new TrustDocumentSource(
                        sourceFilename(source),
                        sha256FromDocId(doc.docId()),
                        doc.metadata().pageCount()));
        writeParserRun(json, backend);
        json.writeArrayFieldStart("units");
        int id = 1;
        for (ParsedSection section : doc.sections()) {
            writeParsedUnit(json, "u" + id++, section);
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    private static String sourceFilename(Path source) {
        Path filename = source.getFileName();
        if (filename == null || filename.toString().isBlank()) {
            throw new IllegalArgumentException("source must include a filename");
        }
        return filename.toString();
    }

    private static void writeSource(JsonGenerator json, TrustDocumentSource source) throws IOException {
        json.writeObjectFieldStart("source");
        json.writeStringField("filename", source.filename());
        json.writeStringField("sha256", source.sha256());
        json.writeNumberField("pageCount", source.pageCount());
        json.writeEndObject();
    }

    private static void writeParserRun(JsonGenerator json, String backend) throws IOException {
        json.writeObjectFieldStart("parserRun");
        json.writeStringField("backend", backend);
        json.writeEndObject();
    }

    private static void writeUnit(JsonGenerator json, TrustUnit unit) throws IOException {
        json.writeStartObject();
        json.writeStringField("id", unit.id());
        json.writeStringField("type", unit.type());
        if (!unit.text().isEmpty()) {
            json.writeStringField("text", unit.text());
        }
        if (!unit.rows().isEmpty()) {
            json.writeObjectField("rows", unit.rows());
        }
        if (unit.evidence().blockKind().isPresent()) {
            json.writeStringField("blockKind", unit.evidence().blockKind().get().name());
        }
        writeLocation(json, unit.evidence().location());
        if (unit.evidence().boundingBox().isPresent()) {
            writeBoundingBox(json, unit.evidence().boundingBox().get());
        }
        json.writeEndObject();
    }

    private static void writeParsedUnit(JsonGenerator json, String id, ParsedSection section) throws IOException {
        switch (section) {
            case TextSection text -> {
                json.writeStartObject();
                json.writeStringField("id", id);
                json.writeStringField("type", "text");
                json.writeStringField("text", text.text());
                json.writeStringField("blockKind", text.kind().name());
                writeLocation(json, text.location());
                if (text.boundingBox().isPresent()) {
                    writeBoundingBox(json, text.boundingBox().get());
                }
                json.writeEndObject();
            }
            case TableSection table -> {
                json.writeStartObject();
                json.writeStringField("id", id);
                json.writeStringField("type", "table");
                json.writeObjectField("rows", table.rows());
                writeLocation(json, table.location());
                json.writeEndObject();
            }
            case FigureSection figure -> {
                json.writeStartObject();
                json.writeStringField("id", id);
                json.writeStringField("type", "figure");
                json.writeStringField("text", figure.caption());
                writeLocation(json, figure.location());
                json.writeEndObject();
            }
        }
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

    private static String sha256FromDocId(String docId) {
        if (docId.startsWith("sha256:")) {
            return docId.substring("sha256:".length());
        }
        throw new IllegalArgumentException("ParsedDocument docId must be sha256-backed");
    }
}
