package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import ai.doctruth.LlmProvider;
import ai.doctruth.OpenAiProvider;
import ai.doctruth.ProviderRequest;
import ai.doctruth.ProviderResponse;
import ai.doctruth.ProviderUsage;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliExtractOptionsTest {

    @TempDir
    Path tempDir;

    @Test
    void extractAcceptsProviderPromptBaseUrlAndExplicitRequiredFields() throws Exception {
        Path pdf = samplePdf();
        Path schema = schemaFile();
        Path out = tempDir.resolve("run");
        var seenProvider = new AtomicReference<ProviderConfig>();
        var seenPrompt = new AtomicReference<String>();
        var cli = cliWithProviderFactory(options -> {
            seenProvider.set(options);
            return providerCapturingPrompt(seenPrompt);
        });

        int code = cli.run(new String[] {
            "extract",
            pdf.toString(),
            "-s",
            schema.toString(),
            "-o",
            out.toString(),
            "--provider",
            "openai",
            "--model",
            "gpt-test",
            "--base-url",
            "https://example.test/v1",
            "--require",
            "partyA,totalValue",
            "--prompt",
            "Extract contract fields."
        });

        assertThat(code).isZero();
        assertThat(seenProvider.get().model()).contains("gpt-test");
        assertThat(seenProvider.get().baseUrl()).contains(URI.create("https://example.test/v1"));
        assertThat(seenPrompt.get()).isEqualTo("Extract contract fields.");
        assertThat(Files.readString(out.resolve("audit.json")))
                .contains("partyA")
                .contains("totalValue");
    }

    private TestCli cliWithProviderFactory(DocTruthCli.ProviderFactory providers) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var cli = new DocTruthCli(
                Map.of("OPENAI_API_KEY", "test"),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                spec -> "{}",
                providers);
        return new TestCli(cli, out, err);
    }

    private static LlmProvider providerCapturingPrompt(AtomicReference<String> seenPrompt) {
        return new OpenAiProvider("test", URI.create("http://localhost"), "test-model") {
            @Override
            public ProviderResponse complete(ProviderRequest request) {
                seenPrompt.set(request.systemPrompt());
                return new ProviderResponse(
                        "{\"partyA\":\"Acme Industrial Materials Pty Ltd\",\"totalValue\":\"AUD 2,450,000\"}",
                        new ProviderUsage(1, 1, "test-model"));
            }
        };
    }

    private Path schemaFile() throws Exception {
        Path schema = tempDir.resolve("contract.schema.json");
        Files.writeString(schema, """
                {
                  "type": "object",
                  "properties": {
                    "partyA": { "type": "string" },
                    "totalValue": { "type": "string" }
                  },
                  "required": ["partyA", "totalValue"]
                }
                """);
        return schema;
    }

    private Path samplePdf() throws Exception {
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
                cs.endText();
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private record TestCli(DocTruthCli delegate, ByteArrayOutputStream outBytes, ByteArrayOutputStream errBytes) {
        int run(String[] args) {
            return delegate.run(args);
        }
    }
}
