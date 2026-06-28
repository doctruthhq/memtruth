package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import ai.doctruth.spi.OcrEngine;
import ai.doctruth.spi.OcrPageResult;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocTruthHappyPathTest {

    @TempDir
    Path tempDir;

    @Test
    void documentFirstFlowExtractsWithEvidenceFromParsedDocument() throws Exception {
        var calls = new AtomicInteger();

        var result = DocTruth.withProvider(provider(calls))
                .from(parsedDoc())
                .extract("Extract the candidate.", Candidate.class)
                .withEvidence()
                .run();

        assertThat(result.value()).isEqualTo(new Candidate("Alex Chen"));
        assertThat(result.citation("name").exactQuote()).contains("Alex Chen");
        assertThat(result.confidence()).containsKey("name");
        assertThat(result.provenance().model()).isEqualTo("openai");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void documentFirstFlowExtractsWithEvidenceFromPdfPath() throws Exception {
        Path pdf = samplePdf();

        var result = DocTruth.withProvider(provider(new AtomicInteger()))
                .fromPdf(pdf)
                .extract("Extract the candidate.", Candidate.class)
                .withEvidence()
                .run();

        assertThat(result.value()).isEqualTo(new Candidate("Alex Chen"));
        assertThat(result.citation("name").location().pageStart()).isEqualTo(1);
    }

    @Test
    void clientParsesSupportedDocumentInputs() throws Exception {
        var client = DocTruth.withProvider(provider(new AtomicInteger()));
        Path csv = tempDir.resolve("candidate.csv");
        Files.writeString(csv, "name\nAlex Chen\n");

        assertThat(client.fromPdf(samplePdf())).isNotNull();
        assertThat(client.fromPdf(samplePdf().toString())).isNotNull();
        assertThat(client.parsePdf(samplePdf().toString())).isNotNull();
        assertThat(client.fromCsv(csv)).isNotNull();
        assertThat(client.fromDocx(sampleDocx())).isNotNull();
        assertThat(client.fromXlsx(sampleXlsx())).isNotNull();
    }

    @Test
    void clientParsesPdfWithOcrEngine() throws Exception {
        var calls = new AtomicInteger();
        OcrEngine ocr = (BufferedImage image, int page) -> {
            calls.incrementAndGet();
            return new OcrPageResult("Name: Alex Chen", 0.9, List.of(), page);
        };

        var document = DocTruth.withProvider(provider(new AtomicInteger())).fromPdf(blankPdf(), ocr);

        assertThat(calls).hasValue(1);
        assertThat(document).isNotNull();
    }

    @Test
    void clientParsesPdfStringWithOcrEngine() throws Exception {
        var calls = new AtomicInteger();
        OcrEngine ocr = (BufferedImage image, int page) -> {
            calls.incrementAndGet();
            return new OcrPageResult("Name: Alex Chen", 0.9, List.of(), page);
        };

        var document = DocTruth.withProvider(provider(new AtomicInteger()))
                .fromPdf(blankPdf().toString(), ocr);

        assertThat(calls).hasValue(1);
        assertThat(document).isNotNull();
    }

    @Test
    void documentFirstFlowKeepsAdvancedExtractionOptions() throws Exception {
        var result = DocTruth.withProvider(provider(new AtomicInteger()))
                .from(parsedDoc())
                .extract("Extract the candidate.", Candidate.class)
                .withSourcePublishedAt(Instant.EPOCH)
                .withMaxRetries(2)
                .withContextStrategy(new PriorityTruncate(List.of("Name"), 100, OverBudgetPolicy.STRICT))
                .run();

        assertThat(result.value()).isEqualTo(new Candidate("Alex Chen"));
        assertThat(result.provenance().sourcePublishedAt()).contains(Instant.EPOCH);
    }

    @Test
    void documentFirstJsonSchemaFlowExtractsWithEvidence() throws Exception {
        var schema = JsonSchema.from("""
                {
                  "type": "object",
                  "properties": {
                    "name": { "type": "string" }
                  },
                  "required": ["name"],
                  "additionalProperties": false
                }
                """);

        var result = DocTruth.withProvider(provider(new AtomicInteger()))
                .from(parsedDoc())
                .extractJson("Extract the candidate.", schema)
                .withEvidence()
                .requireCitation("name")
                .withSourcePublishedAt(Instant.EPOCH)
                .withMaxRetries(1)
                .withContextStrategy(new PriorityTruncate(List.of("Name"), 100, OverBudgetPolicy.STRICT))
                .runJson();

        assertThat(result.value().get("name").asText()).isEqualTo("Alex Chen");
        assertThat(result.requireCitation("name").exactQuote()).contains("Alex Chen");
        assertThat(result.provenance().sourcePublishedAt()).contains(Instant.EPOCH);
    }

    @Test
    void withOpenAiApiKeyCreatesClient() {
        assertThat(DocTruth.withOpenAi("test-key")).isNotNull();
    }

    @Test
    void providerFactoriesCreateProviderBackedClients() {
        assertThat(DocTruth.withProvider(LlmProviders.openAi("test-key"))).isNotNull();
        assertThat(DocTruth.withProvider(LlmProviders.openAiCompatible(
                        "test-key", URI.create("http://localhost/v1/chat/completions"), "local-model")))
                .isNotNull();
        assertThat(DocTruth.withProvider(LlmProviders.anthropic("test-key"))).isNotNull();
        assertThat(DocTruth.withProvider(LlmProviders.gemini("test-key"))).isNotNull();
        assertThat(DocTruth.withProvider(LlmProviders.deepSeek("test-key"))).isNotNull();
    }

    @Test
    void providerFactoriesKeepValidationActionable() {
        assertThatThrownBy(() -> LlmProviders.openAi(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");
        assertThatNullPointerException()
                .isThrownBy(() -> LlmProviders.openAiCompatible("test-key", null, "local-model"))
                .withMessageContaining("endpoint");
    }

    @Test
    void withOpenAiEnvCreatesClientOrReportsMissingKey() {
        var apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            assertThatThrownBy(DocTruth::withOpenAi)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OPENAI_API_KEY");
        } else {
            assertThat(DocTruth.withOpenAi()).isNotNull();
        }
    }

    @Test
    void withOpenAiRejectsBlankApiKey() {
        assertThatThrownBy(() -> DocTruth.withOpenAi(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void withProviderRejectsNullProvider() {
        assertThatNullPointerException()
                .isThrownBy(() -> DocTruth.withProvider(null))
                .withMessageContaining("provider");
    }

    private static ParsedDocument parsedDoc() {
        var loc = new SourceLocation(1, 1, 1, 1, 0);
        var section = new TextSection("Name: Alex Chen", loc);
        var meta = new DocumentMetadata("candidate.pdf", 1, Optional.empty());
        return new ParsedDocument("doc-1", List.of(section), meta);
    }

    private static LlmProvider provider(AtomicInteger calls) {
        return new OpenAiProvider("test", URI.create("http://localhost"), "test-model") {
            @Override
            public ProviderResponse complete(ProviderRequest request) {
                calls.incrementAndGet();
                return new ProviderResponse("{\"name\":\"Alex Chen\"}", new ProviderUsage(10, 3, "test-model"));
            }
        };
    }

    private Path samplePdf() throws Exception {
        Path path = tempDir.resolve("candidate.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var cs = new PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 720);
                cs.showText("Name: Alex Chen");
                cs.endText();
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path blankPdf() throws Exception {
        Path path = tempDir.resolve("blank.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(path.toFile());
        }
        return path;
    }

    private Path sampleDocx() throws Exception {
        Path path = tempDir.resolve("candidate.docx");
        try (var docx = new XWPFDocument()) {
            var paragraph = docx.createParagraph();
            paragraph.createRun().setText("Name: Alex Chen");
            try (var out = Files.newOutputStream(path)) {
                docx.write(out);
            }
        }
        return path;
    }

    private Path sampleXlsx() throws Exception {
        Path path = tempDir.resolve("candidate.xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Candidates");
            sheet.createRow(0).createCell(0).setCellValue("Name");
            sheet.createRow(1).createCell(0).setCellValue("Alex Chen");
            try (var out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
        }
        return path;
    }

    private record Candidate(String name) {}
}
