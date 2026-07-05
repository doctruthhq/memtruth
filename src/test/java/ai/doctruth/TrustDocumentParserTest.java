package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustDocumentParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void parsesPdfIntoTrustDocumentWithOpenDataLoaderByDefault() throws Exception {
        Path pdf = writePdf("TrustDocument default path");

        TrustDocument doc = TrustDocumentParser.parse(pdf);

        assertThat(doc.source().filename()).isEqualTo(pdf.getFileName().toString());
        assertThat(doc.source().sha256()).hasSize(64);
        assertThat(doc.parserRun().backend()).isEqualTo("opendataloader");
        assertThat(doc.units()).isNotEmpty();
        assertThat(doc.units()).anySatisfy(unit -> assertThat(unit.text()).contains("TrustDocument default path"));
    }

    @Test
    void jsonCarriesAuditReadySourceParserLocationAndBboxFields() throws Exception {
        Path pdf = writePdf("Audit-ready TrustDocument JSON");
        TrustDocument doc = TrustDocumentParser.parse(pdf);

        var tree = MAPPER.readTree(TrustDocumentJson.toJson(doc));

        assertThat(tree.path("schemaVersion").asText()).isEqualTo("doctruth.trust-document.v1");
        assertThat(tree.path("source").path("sha256").asText()).hasSize(64);
        assertThat(tree.path("parserRun").path("backend").asText()).isEqualTo("opendataloader");
        var unit = tree.path("units").get(0);
        assertThat(unit.path("location").path("pageStart").asInt()).isEqualTo(1);
        assertThat(unit.has("boundingBox")).isTrue();
    }

    @Test
    void fromParsedMapsTextTableAndFigureUnits() {
        var location = new SourceLocation(1, 1, 1, 1, 0);
        var metadata = new DocumentMetadata("contract.pdf", 1, Optional.empty());
        var parsed = new ParsedDocument(
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                List.of(
                        new TextSection("Intro", location),
                        new TableSection(List.of(List.of("A", "B")), location),
                        new FigureSection("Chart", location)),
                metadata);

        TrustDocument doc = TrustDocument.fromParsed(parsed, Path.of("contract.pdf"), PdfParserBackend.OPENDATALOADER);
        var json = TrustDocumentJson.toJson(doc);

        assertThat(doc.units()).extracting(TrustUnit::type).containsExactly("text", "table", "figure");
        assertThat(json).contains("\"rows\"", "\"text\" : \"Chart\"");
    }

    @Test
    void jsonCanStreamDirectlyFromParsedDocument() throws Exception {
        var location = new SourceLocation(1, 1, 1, 1, 0);
        var metadata = new DocumentMetadata("contract.pdf", 1, Optional.empty());
        var parsed = new ParsedDocument(
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                List.of(
                        new TextSection(
                                "Intro", location, BlockKind.BODY, Optional.of(new BoundingBox(10, 20, 30, 40))),
                        new TableSection(List.of(List.of("A", "B")), location),
                        new FigureSection("Chart", location)),
                metadata);

        String materialized = TrustDocumentJson.toJson(
                TrustDocument.fromParsed(parsed, Path.of("contract.pdf"), PdfParserBackend.OPENDATALOADER));
        String streamed = TrustDocumentJson.toJson(parsed, Path.of("contract.pdf"), PdfParserBackend.OPENDATALOADER);

        assertThat(MAPPER.readTree(streamed)).isEqualTo(MAPPER.readTree(materialized));
    }

    @Test
    void fromParsedUsesExplicitSourcePathForSourceFilename() throws Exception {
        var location = new SourceLocation(1, 1, 1, 1, 0);
        var metadata = new DocumentMetadata("internal-temp-name.pdf", 1, Optional.empty());
        var parsed = new ParsedDocument(
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                List.of(new TextSection("Intro", location)),
                metadata);

        TrustDocument doc = TrustDocument.fromParsed(
                parsed, Path.of("/documents/customer-contract.pdf"), PdfParserBackend.OPENDATALOADER);
        String streamed = TrustDocumentJson.toJson(
                parsed, Path.of("/documents/customer-contract.pdf"), PdfParserBackend.OPENDATALOADER);

        assertThat(doc.source().filename()).isEqualTo("customer-contract.pdf");
        assertThat(MAPPER.readTree(streamed).path("source").path("filename").asText())
                .isEqualTo("customer-contract.pdf");
    }

    @Test
    void writerOverloadsMatchStringJson() throws Exception {
        var location = new SourceLocation(1, 1, 1, 1, 0);
        var metadata = new DocumentMetadata("contract.pdf", 1, Optional.empty());
        var parsed = new ParsedDocument(
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                List.of(new TextSection("Intro", location)),
                metadata);
        var doc = TrustDocument.fromParsed(parsed, Path.of("contract.pdf"), PdfParserBackend.OPENDATALOADER);
        var materializedDoc = TrustDocumentJson.toJson(doc);
        var materializedParsed =
                TrustDocumentJson.toJson(parsed, Path.of("contract.pdf"), PdfParserBackend.OPENDATALOADER);
        var docWriter = new StringWriter();
        var parsedWriter = new StringWriter();

        TrustDocumentJson.writeJson(doc, docWriter);
        TrustDocumentJson.writeJson(parsed, Path.of("contract.pdf"), PdfParserBackend.OPENDATALOADER, parsedWriter);

        assertThat(MAPPER.readTree(docWriter.toString())).isEqualTo(MAPPER.readTree(materializedDoc));
        assertThat(MAPPER.readTree(parsedWriter.toString())).isEqualTo(MAPPER.readTree(materializedParsed));
    }

    @Test
    void sourceValidationRejectsInvalidIdentity() {
        assertThatThrownBy(() -> new TrustDocumentSource("", "0".repeat(64), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filename");
        assertThatThrownBy(() -> new TrustDocumentSource("x.pdf", "not-sha", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sha256");
        assertThatThrownBy(() -> new TrustDocumentSource("x.pdf", "0".repeat(64), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageCount");
    }

    @Test
    void parserCannotBeInstantiated() throws Exception {
        var constructor = TrustDocumentParser.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(AssertionError.class);
    }

    private Path writePdf(String text) throws Exception {
        Path path = tempDir.resolve("sample.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            try (PDPageContentStream content = new PDPageContentStream(document, document.getPage(0))) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            document.save(path.toFile());
        }
        return path;
    }
}
