package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import ai.doctruth.BlockKind;
import ai.doctruth.DocumentMetadata;
import ai.doctruth.FigureSection;
import ai.doctruth.ParsedDocument;
import ai.doctruth.SourceLocation;
import ai.doctruth.TableSection;
import ai.doctruth.TextSection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void parsedDocumentJsonHandlesAllSectionTypes() throws Exception {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var doc = new ParsedDocument(
                "doc",
                java.util.List.of(
                        new TextSection("hello", loc, BlockKind.BODY),
                        new TableSection(java.util.List.of(java.util.List.of("a")), loc),
                        new FigureSection("chart", loc)),
                new DocumentMetadata("sample.pdf", 1, Optional.empty()));

        var tree = MAPPER.readTree(ParsedDocumentJson.toJson(doc));

        assertThat(tree.path("sections").get(0).path("type").asText()).isEqualTo("text");
        assertThat(tree.path("sections").get(1).path("type").asText()).isEqualTo("table");
        assertThat(tree.path("sections").get(2).path("type").asText()).isEqualTo("figure");
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
        assertThat(DocumentParsers.parse(Path.of("fixtures/docx/default.docx"))
                        .metadata()
                        .sourceFilename())
                .isEqualTo("default.docx");
        assertThat(DocumentParsers.parse(Path.of("fixtures/xlsx/ssg_skills_framework.xlsx"))
                        .sections())
                .isNotEmpty();
        assertThat(DocumentParsers.parse(Path.of("fixtures/csv/iris.csv")).sections())
                .isNotEmpty();

        Path invalidPdf = tempDir.resolve("broken.pdf");
        Files.writeString(invalidPdf, "not a pdf");
        assertThatThrownBy(() -> DocumentParsers.parse(invalidPdf))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("failed to parse");
        assertThatThrownBy(() -> DocumentParsers.parse(tempDir.resolve("README")))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("unsupported document format");
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
