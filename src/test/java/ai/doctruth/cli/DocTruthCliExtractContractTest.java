package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import ai.doctruth.LlmProvider;
import ai.doctruth.OpenAiProvider;
import ai.doctruth.ProviderRequest;
import ai.doctruth.ProviderResponse;
import ai.doctruth.ProviderUsage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocTruthCliExtractContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void extractWritesTrustDocumentLinkedRunManifest() throws Exception {
        Path pdf = samplePdf();
        Path schema = schemaFile();
        Path out = tempDir.resolve("run");
        var cli = cliWithProvider(cannedProvider());

        int code = cli.run(new String[] {"extract", pdf.toString(), "-s", schema.toString(), "-o", out.toString()});

        assertThat(code).isZero();
        var trust = MAPPER.readTree(Files.readString(out.resolve("trust-document.json")));
        var audit = MAPPER.readTree(Files.readString(out.resolve("audit.json")));
        var firstDerivation = audit.path("prov:wasDerivedFrom").get(0);
        assertThat(firstDerivation.path("doctruth:sourceDocId").asText())
                .isEqualTo(trust.path("docId").asText());
        assertThat(firstDerivation.path("doctruth:sourceUnitId").asText()).isNotBlank();
        assertThat(trust.path("units"))
                .anySatisfy(unit -> assertThat(unit.path("id").asText())
                        .isEqualTo(firstDerivation.path("doctruth:sourceUnitId").asText()));
        var manifest = MAPPER.readTree(Files.readString(out.resolve("manifest.json")));
        assertThat(manifest.path("schemaVersion").asText()).isEqualTo("doctruth.extract-run.v1");
        assertThat(manifest.has("source")).isFalse();
        assertThat(manifest.path("sourceFilename").asText())
                .isEqualTo(pdf.getFileName().toString());
        assertThat(manifest.path("sourceSha256").asText()).isNotBlank();
        assertThat(manifest.path("artifacts").path("trustDocument").asText()).isEqualTo("trust-document.json");
    }

    @Test
    void extractRequiresNestedSchemaLeafCitationsByDefault() throws Exception {
        Path pdf = samplePdf();
        Path schema = nestedSchemaFile();
        Path out = tempDir.resolve("nested-run");
        var cli = cliWithProvider(providerReturning("{\"party\":{\"name\":\"Acme Industrial Materials Pty Ltd\"}}"));

        int code = cli.run(new String[] {"extract", pdf.toString(), "-s", schema.toString(), "-o", out.toString()});

        assertThat(code).isZero();
        var audit = MAPPER.readTree(Files.readString(out.resolve("audit.json")));
        assertThat(audit.path("prov:wasDerivedFrom"))
                .anySatisfy(entry ->
                        assertThat(entry.path("doctruth:fieldPath").asText()).isEqualTo("party.name"));
    }

    @Test
    void extractRequiresArrayItemSchemaLeafCitationsByDefault() throws Exception {
        Path pdf = samplePdf();
        Path schema = arraySchemaFile();
        Path out = tempDir.resolve("array-run");
        var cli = cliWithProvider(providerReturning("{\"items\":[{\"name\":\"Premium Support Plan\"}]}"));

        int code = cli.run(new String[] {"extract", pdf.toString(), "-s", schema.toString(), "-o", out.toString()});

        assertThat(code).isZero();
        var audit = MAPPER.readTree(Files.readString(out.resolve("audit.json")));
        assertThat(audit.path("prov:wasDerivedFrom"))
                .anySatisfy(entry ->
                        assertThat(entry.path("doctruth:fieldPath").asText()).isEqualTo("items[0].name"));
    }

    @Test
    void extractEvidenceFirstUnwrapsValuesAndCitesExactQuotes() throws Exception {
        Path pdf = samplePdf();
        Path schema = schemaFile();
        Path out = tempDir.resolve("evidence-first-run");
        var cli = cliWithProvider(evidenceFirstProvider());

        int code = cli.run(new String[] {
            "extract", pdf.toString(), "-s", schema.toString(), "-o", out.toString(), "--evidence-first"
        });

        assertThat(code).isZero();
        var result = MAPPER.readTree(Files.readString(out.resolve("result.json")));
        assertThat(result.path("partyA").asText()).isEqualTo("Acme Industrial Materials Pty Ltd");
        var audit = MAPPER.readTree(Files.readString(out.resolve("audit.json")));
        assertThat(audit.path("prov:wasDerivedFrom"))
                .anySatisfy(entry -> assertThat(entry.path("prov:value").asText())
                        .isEqualTo("Party A: Acme Industrial Materials Pty Ltd"));
    }

    private TestCli cliWithProvider(LlmProvider provider) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var cli = new DocTruthCli(
                Map.of("OPENAI_API_KEY", "test"),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                spec -> "{}",
                opts -> provider);
        return new TestCli(cli, out);
    }

    private static LlmProvider cannedProvider() {
        return providerReturning("{\"partyA\":\"Acme Industrial Materials Pty Ltd\",\"totalValue\":\"AUD 2,450,000\"}");
    }

    private static LlmProvider providerReturning(String json) {
        return new OpenAiProvider("test", URI.create("http://localhost"), "test-model") {
            @Override
            public ProviderResponse complete(ProviderRequest request) {
                return new ProviderResponse(json, new ProviderUsage(1, 1, "test-model"));
            }
        };
    }

    private static LlmProvider evidenceFirstProvider() {
        return new OpenAiProvider("test", URI.create("http://localhost"), "test-model") {
            @Override
            public ProviderResponse complete(ProviderRequest request) {
                assertThat(request.responseSchema()
                                .path("properties")
                                .path("partyA")
                                .path("properties")
                                .has("value"))
                        .isTrue();
                assertThat(request.responseSchema()
                                .path("properties")
                                .path("partyA")
                                .path("properties")
                                .has("exactQuote"))
                        .isTrue();
                return new ProviderResponse("""
                        {
                          "partyA": {
                            "value": "Acme Industrial Materials Pty Ltd",
                            "exactQuote": "Party A: Acme Industrial Materials Pty Ltd"
                          },
                          "totalValue": {
                            "value": "AUD 2,450,000",
                            "exactQuote": "Total Value: AUD 2,450,000"
                          }
                        }
                        """, new ProviderUsage(1, 1, "test-model"));
            }
        };
    }

    private Path schemaFile() throws IOException {
        Path schema = tempDir.resolve("contract.schema.json");
        Files.writeString(schema, """
                {
                  "type": "object",
                  "properties": {
                    "partyA": { "type": "string" },
                    "totalValue": { "type": "string" }
                  },
                  "required": ["partyA", "totalValue"],
                  "additionalProperties": false
                }
                """);
        return schema;
    }

    private Path nestedSchemaFile() throws IOException {
        Path schema = tempDir.resolve("nested.schema.json");
        Files.writeString(schema, """
                {
                  "type": "object",
                  "properties": {
                    "party": {
                      "type": "object",
                      "properties": {
                        "name": { "type": "string" }
                      },
                      "required": ["name"]
                    }
                  },
                  "required": ["party"],
                  "additionalProperties": false
                }
                """);
        return schema;
    }

    private Path arraySchemaFile() throws IOException {
        Path schema = tempDir.resolve("array.schema.json");
        Files.writeString(schema, """
                {
                  "type": "object",
                  "properties": {
                    "items": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "name": { "type": "string" }
                        },
                        "required": ["name"],
                        "additionalProperties": false
                      }
                    }
                  },
                  "required": ["items"],
                  "additionalProperties": false
                }
                """);
        return schema;
    }

    private Path samplePdf() throws IOException {
        Path path = tempDir.resolve("contract.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 720);
                cs.showText("Party A: Acme Industrial Materials Pty Ltd");
                cs.newLineAtOffset(0, -18);
                cs.showText("Total Value: AUD 2,450,000");
                cs.newLineAtOffset(0, -18);
                cs.showText("Line item: Premium Support Plan");
                cs.endText();
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private record TestCli(DocTruthCli delegate, ByteArrayOutputStream outBytes) {
        int run(String[] args) {
            return delegate.run(args);
        }
    }
}
