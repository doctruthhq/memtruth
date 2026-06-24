package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.doctruth.BlockKind;
import ai.doctruth.BoundingBox;
import ai.doctruth.DocumentMetadata;
import ai.doctruth.FigureSection;
import ai.doctruth.ParsedDocument;
import ai.doctruth.SourceLocation;
import ai.doctruth.TableSection;
import ai.doctruth.TextSection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void parsedDocumentJsonHandlesAllSectionTypes() throws Exception {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var figureBox = new BoundingBox(10, 20, 110, 40);
        var doc = new ParsedDocument(
                "doc",
                java.util.List.of(
                        new TextSection("hello", loc, BlockKind.BODY),
                        new TableSection(java.util.List.of(java.util.List.of("a")), loc),
                        new FigureSection("chart", loc, Optional.of(figureBox))),
                new DocumentMetadata("sample.pdf", 1, Optional.empty()));

        var tree = MAPPER.readTree(ParsedDocumentJson.toJson(doc));

        assertThat(tree.path("sections").get(0).path("type").asText()).isEqualTo("text");
        assertThat(tree.path("sections").get(1).path("type").asText()).isEqualTo("table");
        assertThat(tree.path("sections").get(2).path("type").asText()).isEqualTo("figure");
        assertThat(tree.path("sections").get(2).path("boundingBox").path("x0").asDouble()).isEqualTo(10.0);
    }

    @Test
    void parsedDocumentMarkdownRendersStableSourceFaithfulMarkdown() {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var doc = new ParsedDocument(
                "doc",
                java.util.List.of(
                        new TextSection("Work Experience", loc, BlockKind.HEADING),
                        new TextSection("August 2020 to February 2021", loc, BlockKind.HEADING),
                        new TextSection("1. Built _source_ backed parser\nwith wrapped continuation", loc, BlockKind.LIST),
                        new TableSection(java.util.List.of(
                                java.util.List.of("Name", "Role"),
                                java.util.List.of("Alex", "Parser | QA")),
                                loc),
                        new FigureSection("Pipeline diagram", loc)),
                new DocumentMetadata("sample.pdf", 1, Optional.empty()));

        assertThat(ParsedDocumentMarkdown.toMarkdown(doc))
                .isEqualTo(
                        """
                        ## Work Experience

                        August 2020 to February 2021

                        1. Built \\_source\\_ backed parser with wrapped continuation

                        | Name | Role |
                        | --- | --- |
                        | Alex | Parser \\| QA |

                        [Figure: Pipeline diagram]
                        """);
    }

    @Test
    void parsedDocumentMarkdownUsesBboxReadingOrderAndRejoinsListContinuations() {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var doc = new ParsedDocument(
                "doc",
                java.util.List.of(
                        new TextSection(
                                "• Lead production planning for day-",
                                loc,
                                BlockKind.LIST,
                                Optional.of(new BoundingBox(180, 420, 850, 440))),
                        new TextSection(
                                "Contact: 011-11212633",
                                loc,
                                BlockKind.BODY,
                                Optional.of(new BoundingBox(220, 180, 500, 195))),
                        new TextSection(
                                "Candidate Name",
                                loc,
                                BlockKind.HEADING,
                                Optional.of(new BoundingBox(220, 220, 500, 240))),
                        new TextSection(
                                "to-day shipment release.",
                                loc,
                                BlockKind.BODY,
                                Optional.of(new BoundingBox(210, 443, 650, 458)))),
                new DocumentMetadata("sample.pdf", 1, Optional.empty()));

        assertThat(ParsedDocumentMarkdown.toMarkdown(doc))
                .isEqualTo(
                        """
                        Contact: 011-11212633

                        ## Candidate Name

                        • Lead production planning for day-to-day shipment release.
                        """);
    }

    @Test
    void parsedDocumentMarkdownUsesFigureCaptionBboxReadingOrder() {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var doc = new ParsedDocument(
                "doc",
                java.util.List.of(
                        new TextSection("Body below caption", loc, BlockKind.BODY, Optional.of(new BoundingBox(10, 200, 300, 220))),
                        new FigureSection("Table 1. Revenue", loc, Optional.of(new BoundingBox(10, 100, 300, 120)))),
                new DocumentMetadata("sample.pdf", 1, Optional.empty()));

        assertThat(ParsedDocumentMarkdown.toMarkdown(doc))
                .isEqualTo(
                        """
                        [Figure: Table 1. Revenue]

                        Body below caption
                        """);
    }

    @Test
    void providerFactoryCreatesSupportedProviders() throws Exception {
        var env = Map.of(
                "OPENAI_API_KEY", "openai-key",
                "ANTHROPIC_API_KEY", "anthropic-key",
                "GOOGLE_API_KEY", "gemini-key",
                "DEEPSEEK_API_KEY", "deepseek-key");
        var config = new CliConfig("openai", Optional.empty(), tempDir, env);

        assertThat(Providers.create(new ProviderConfig("openai", Optional.of("gpt-test"), Optional.empty(), config))
                        .name())
                .isEqualTo("openai");
        assertThat(Providers.create(new ProviderConfig("anthropic", Optional.empty(), Optional.empty(), config))
                        .name())
                .isEqualTo("anthropic");
        assertThat(Providers.create(new ProviderConfig("gemini", Optional.empty(), Optional.empty(), config))
                        .name())
                .isEqualTo("gemini");
        assertThat(Providers.create(new ProviderConfig("deepseek", Optional.empty(), Optional.empty(), config))
                        .name())
                .isEqualTo("deepseek");
    }

    @Test
    void providerFactoryHandlesBaseUrlsAndErrors() throws Exception {
        var config = new CliConfig("openai", Optional.empty(), tempDir, Map.of("OPENAI_API_KEY", "openai-key"));

        assertThat(Providers.create(new ProviderConfig(
                                "openai", Optional.empty(), Optional.of(URI.create("https://example.test/v1")), config))
                        .name())
                .isEqualTo("openai");
        assertThat(Providers.create(new ProviderConfig(
                                "openai",
                                Optional.empty(),
                                Optional.of(URI.create("https://example.test/v1/chat/completions")),
                                config))
                        .name())
                .isEqualTo("openai");
        assertThatThrownBy(
                        () -> Providers.create(new ProviderConfig("wat", Optional.empty(), Optional.empty(), config)))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("unsupported provider");
        assertThatThrownBy(() -> Providers.create(new ProviderConfig(
                        "openai",
                        Optional.empty(),
                        Optional.empty(),
                        new CliConfig("openai", Optional.empty(), tempDir, Map.of("OPENAI_API_KEY", " ")))))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("missing OPENAI_API_KEY");
    }

    @Test
    void documentParsersRouteSupportedFormatsAndReportFailures() throws Exception {
        Path docx = writeDocx("default.docx");
        Path xlsx = writeXlsx("skills.xlsx");
        Path csv = writeCsv("iris.csv");

        assertThat(DocumentParsers.parse(docx).metadata().sourceFilename()).isEqualTo("default.docx");
        assertThat(DocumentParsers.parse(xlsx).sections()).isNotEmpty();
        assertThat(DocumentParsers.parse(csv).sections()).isNotEmpty();

        Path invalidPdf = tempDir.resolve("broken.pdf");
        Files.writeString(invalidPdf, "not a pdf");
        assertThatThrownBy(() -> DocumentParsers.parse(invalidPdf))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("failed to parse");
        assertThatThrownBy(() -> DocumentParsers.parse(tempDir.resolve("README")))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("unsupported document format");
    }

    private Path writeDocx(String filename) throws Exception {
        Path path = tempDir.resolve(filename);
        try (var docx = new XWPFDocument()) {
            docx.createParagraph().createRun().setText("hello from docx");
            try (var out = Files.newOutputStream(path)) {
                docx.write(out);
            }
        }
        return path;
    }

    private Path writeXlsx(String filename) throws Exception {
        Path path = tempDir.resolve(filename);
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("skill");
            row.createCell(1).setCellValue("evidence");
            try (var out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
        }
        return path;
    }

    private Path writeCsv(String filename) throws Exception {
        Path path = tempDir.resolve(filename);
        Files.write(path, List.of("species,sepal", "setosa,5.1"));
        return path;
    }

    @Test
    void utilityConstructorsRejectReflectionInstantiation() throws Exception {
        assertUtilityConstructorRejects(Usage.class);
        assertUtilityConstructorRejects(Providers.class);
        assertUtilityConstructorRejects(DocumentParsers.class);
    }

    @Test
    void cliConfigLoadsYamlLikeDefaults() throws Exception {
        Path configPath = tempDir.resolve("doctruth.yml");
        Files.writeString(configPath, "provider: anthropic\nmodel: claude-test\noutput: build/runs\n");

        var config = CliConfig.load(configPath, Map.of());

        assertThat(config.provider()).isEqualTo("anthropic");
        assertThat(config.model()).contains("claude-test");
        assertThat(config.output()).isEqualTo(Path.of("build/runs"));
    }

    private static void assertUtilityConstructorRejects(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(AssertionError.class);
    }
}
