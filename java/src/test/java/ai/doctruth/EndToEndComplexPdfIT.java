package ai.doctruth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * End-to-end integration test for the v0.1.0-alpha pipeline against a non-trivial PDF.
 *
 * <p>Runs four provider variants (Anthropic / OpenAI / Gemini / DeepSeek) plus a bi-temporal
 * round-trip against a 4-page programmatically-generated PDF. WireMock stubs each provider's
 * vendor endpoint with a canned JSON payload matching the {@link Contract} target shape.
 *
 * <p>Asserts the FULL v0.1.0-alpha advertised contract:
 *
 * <ul>
 *   <li>Layer 1 PDF parsing (multi-page, multi-section).
 *   <li>Layer 2 provider call (each of the four providers).
 *   <li>Layer 3 smart context assembly via {@link PriorityTruncate} with WARN_AND_INCLUDE.
 *   <li>Layer 4 fluent API (extract → withProvenance → withBitemporal → run).
 *   <li>Layer 5 audit output: per-field {@link Citation citations}, {@link Provenance bi-temporal},
 *       {@code retries == 0}.
 * </ul>
 *
 * <p>Because filename ends in {@code IT.java} this class runs under {@code mvn verify} via
 * Failsafe, NOT {@code mvn test} (Surefire excludes {@code **&#47;*IT.java}).
 */
class EndToEndComplexPdfIT {

    /** Target shape for the extraction. Two records keep the shape tractable for the IT. */
    record Contract(
            String partyA, String partyB, LocalDate effectiveDate, BigDecimal totalValue, List<ScoringItem> scoring) {}

    record ScoringItem(int rank, String criterion, int weightPercent) {}

    private static final String PARTY_A = "Acme Industrial Materials Pty Ltd";
    private static final String PARTY_B = "BetaCorp Construction Ltd";
    private static final LocalDate EFFECTIVE_DATE = LocalDate.parse("2026-04-01");
    private static final BigDecimal TOTAL_VALUE = new BigDecimal("2450000");
    private static final Instant SOURCE_PUBLISHED_AT = Instant.parse("2026-04-01T00:00:00Z");

    /** Canned LLM JSON payload — same shape every provider returns. */
    private static final String CONTRACT_JSON = "{\"partyA\":\"Acme Industrial Materials Pty Ltd\","
            + "\"partyB\":\"BetaCorp Construction Ltd\","
            + "\"effectiveDate\":\"2026-04-01\","
            + "\"totalValue\":2450000,"
            + "\"scoring\":["
            + "{\"rank\":1,\"criterion\":\"Price\",\"weightPercent\":40},"
            + "{\"rank\":2,\"criterion\":\"Past Performance\",\"weightPercent\":30},"
            + "{\"rank\":3,\"criterion\":\"Schedule Compliance\",\"weightPercent\":20},"
            + "{\"rank\":4,\"criterion\":\"Innovation\",\"weightPercent\":10}"
            + "]}";

    /** Generated once for the whole class to amortise PDF creation cost. */
    private static Path pdfPath;

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                    .dynamicPort())
            .build();

    @BeforeAll
    static void buildPdf() throws IOException {
        pdfPath = Files.createTempFile("doctruth-e2e-", ".pdf");
        writeComplexPdf(pdfPath);
    }

    @AfterAll
    static void cleanupPdf() throws IOException {
        if (pdfPath != null) {
            Files.deleteIfExists(pdfPath);
        }
    }

    @BeforeEach
    void resetWireMock() {
        wm.resetAll();
    }

    private static PriorityTruncate priorityStrategy() {
        return new PriorityTruncate(
                List.of("Qualifications", "Scoring", "Disqualification"), 25_000, OverBudgetPolicy.WARN_AND_INCLUDE);
    }

    private static void assertContractValueEquals(Contract value) {
        assertThat(value.partyA()).isEqualTo(PARTY_A);
        assertThat(value.partyB()).isEqualTo(PARTY_B);
        assertThat(value.effectiveDate()).isEqualTo(EFFECTIVE_DATE);
        assertThat(value.totalValue()).isEqualByComparingTo(TOTAL_VALUE);
        assertThat(value.scoring()).hasSize(4);
        assertThat(value.scoring().get(0)).isEqualTo(new ScoringItem(1, "Price", 40));
        assertThat(value.scoring().get(1)).isEqualTo(new ScoringItem(2, "Past Performance", 30));
        assertThat(value.scoring().get(2)).isEqualTo(new ScoringItem(3, "Schedule Compliance", 20));
        assertThat(value.scoring().get(3)).isEqualTo(new ScoringItem(4, "Innovation", 10));
    }

    private static void assertCommonProvenance(ExtractionResult<Contract> result, String expectedProviderName) {
        assertThat(result.provenance().model()).isEqualTo(expectedProviderName);
        assertThat(result.provenance().sourcePublishedAt()).contains(SOURCE_PUBLISHED_AT);
        assertThat(result.provenance().retries()).isZero();
    }

    private static void assertPartyACitation(ExtractionResult<Contract> result) {
        assertThat(result.citations()).containsKey("partyA");
        var c = result.citations().get("partyA");
        assertThat(c.matchScore()).isGreaterThanOrEqualTo(0.85);
        assertThat(c.location().pageStart()).isEqualTo(1);
    }

    @Nested
    @DisplayName("AnthropicE2E")
    class AnthropicE2E {

        private static final String PATH = "/v1/messages";

        private static final String ANTHROPIC_BODY = "{"
                + "\"id\":\"msg_e2e\","
                + "\"model\":\"claude-sonnet-4-5-e2e\","
                + "\"stop_reason\":\"tool_use\","
                + "\"content\":[{"
                + "\"type\":\"tool_use\","
                + "\"id\":\"toolu_e2e\","
                + "\"name\":\"extract\","
                + "\"input\":"
                + CONTRACT_JSON
                + "}],"
                + "\"usage\":{\"input_tokens\":120,\"cache_creation_input_tokens\":100,"
                + "\"cache_read_input_tokens\":0,\"output_tokens\":18}"
                + "}";

        @Test
        @DisplayName("Anthropic provider extracts a Contract end-to-end with full citations")
        void anthropicEndToEnd() throws Exception {
            wm.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(ANTHROPIC_BODY)));

            var provider = new AnthropicProvider(
                    "test-key", URI.create(wm.getRuntimeInfo().getHttpBaseUrl() + PATH), "claude-sonnet-4-5");
            var doc = PdfDocumentParser.parse(pdfPath);

            var result = DocTruth.from(provider)
                    .extract("Extract the contract terms", Contract.class)
                    .withProvenance()
                    .withBitemporal()
                    .withSourcePublishedAt(SOURCE_PUBLISHED_AT)
                    .withContextStrategy(priorityStrategy())
                    .run(doc);

            assertContractValueEquals(result.value());
            assertCommonProvenance(result, "anthropic");
            assertPartyACitation(result);
            wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
        }
    }

    @Nested
    @DisplayName("OpenAiE2E")
    class OpenAiE2E {

        private static final String PATH = "/v1/chat/completions";

        private static final String OPENAI_BODY = "{"
                + "\"id\":\"chatcmpl-e2e\","
                + "\"model\":\"gpt-4o-e2e\","
                + "\"choices\":[{"
                + "\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":"
                + jsonString(CONTRACT_JSON)
                + "},"
                + "\"finish_reason\":\"stop\""
                + "}],"
                + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":18,\"total_tokens\":138}"
                + "}";

        @Test
        @DisplayName("OpenAI provider extracts a Contract end-to-end with full citations")
        void openAiEndToEnd() throws Exception {
            wm.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(OPENAI_BODY)));

            var provider = new OpenAiProvider(
                    "test-key", URI.create(wm.getRuntimeInfo().getHttpBaseUrl() + PATH), "gpt-4o");
            var doc = PdfDocumentParser.parse(pdfPath);

            var result = DocTruth.from(provider)
                    .extract("Extract the contract terms", Contract.class)
                    .withProvenance()
                    .withBitemporal()
                    .withSourcePublishedAt(SOURCE_PUBLISHED_AT)
                    .withContextStrategy(priorityStrategy())
                    .run(doc);

            assertContractValueEquals(result.value());
            assertCommonProvenance(result, "openai");
            assertPartyACitation(result);
            wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
        }
    }

    @Nested
    @DisplayName("GeminiE2E")
    class GeminiE2E {

        private static final String MODEL = "gemini-1.5-pro";
        private static final String PATH = "/v1beta/models/" + MODEL + ":generateContent";

        private static final String GEMINI_BODY = "{"
                + "\"candidates\":[{"
                + "\"content\":{\"role\":\"model\",\"parts\":[{\"text\":"
                + jsonString(CONTRACT_JSON)
                + "}]},"
                + "\"finishReason\":\"STOP\",\"index\":0}],"
                + "\"usageMetadata\":{\"promptTokenCount\":120,\"candidatesTokenCount\":18,\"totalTokenCount\":138},"
                + "\"modelVersion\":\"gemini-1.5-pro-e2e\""
                + "}";

        @Test
        @DisplayName("Gemini provider extracts a Contract end-to-end with full citations")
        void geminiEndToEnd() throws Exception {
            wm.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(GEMINI_BODY)));

            var provider = new GeminiProvider(
                    "test-key", URI.create(wm.getRuntimeInfo().getHttpBaseUrl()), MODEL);
            var doc = PdfDocumentParser.parse(pdfPath);

            var result = DocTruth.from(provider)
                    .extract("Extract the contract terms", Contract.class)
                    .withProvenance()
                    .withBitemporal()
                    .withSourcePublishedAt(SOURCE_PUBLISHED_AT)
                    .withContextStrategy(priorityStrategy())
                    .run(doc);

            assertContractValueEquals(result.value());
            assertCommonProvenance(result, "gemini");
            assertPartyACitation(result);
            wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
        }
    }

    @Nested
    @DisplayName("DeepSeekE2E")
    class DeepSeekE2E {

        private static final String PATH = "/v1/chat/completions";

        private static final String DEEPSEEK_BODY = "{"
                + "\"id\":\"chatcmpl-ds-e2e\","
                + "\"model\":\"deepseek-chat-e2e\","
                + "\"choices\":[{"
                + "\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":"
                + jsonString(CONTRACT_JSON)
                + "},"
                + "\"finish_reason\":\"stop\""
                + "}],"
                + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":18,\"total_tokens\":138}"
                + "}";

        @Test
        @DisplayName("DeepSeek provider extracts a Contract end-to-end with full citations")
        void deepSeekEndToEnd() throws Exception {
            wm.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(DEEPSEEK_BODY)));

            var provider = new DeepSeekProvider(
                    "test-key", URI.create(wm.getRuntimeInfo().getHttpBaseUrl() + PATH), "deepseek-chat");
            var doc = PdfDocumentParser.parse(pdfPath);

            var result = DocTruth.from(provider)
                    .extract("Extract the contract terms", Contract.class)
                    .withProvenance()
                    .withBitemporal()
                    .withSourcePublishedAt(SOURCE_PUBLISHED_AT)
                    .withContextStrategy(priorityStrategy())
                    .run(doc);

            assertContractValueEquals(result.value());
            assertCommonProvenance(result, "deepseek");
            assertPartyACitation(result);
            wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
        }
    }

    @Nested
    @DisplayName("BiTemporal")
    class BiTemporal {

        private static final String PATH = "/v1/messages";

        private static final String ANTHROPIC_BODY = "{"
                + "\"id\":\"msg_bitemporal\","
                + "\"model\":\"claude-sonnet-4-5-bitemporal\","
                + "\"stop_reason\":\"tool_use\","
                + "\"content\":[{"
                + "\"type\":\"tool_use\","
                + "\"id\":\"toolu_bitemporal\","
                + "\"name\":\"extract\","
                + "\"input\":"
                + CONTRACT_JSON
                + "}],"
                + "\"usage\":{\"input_tokens\":120,\"cache_creation_input_tokens\":100,"
                + "\"cache_read_input_tokens\":0,\"output_tokens\":18}"
                + "}";

        @Test
        @DisplayName("extractedAt is captured at run-time AND sourcePublishedAt round-trips the "
                + "explicit configured Instant — pins the bi-temporal differentiator")
        void bitemporalRoundTrip() throws Exception {
            wm.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(ANTHROPIC_BODY)));

            var provider = new AnthropicProvider(
                    "test-key", URI.create(wm.getRuntimeInfo().getHttpBaseUrl() + PATH), "claude-sonnet-4-5");
            var doc = PdfDocumentParser.parse(pdfPath);

            var beforeRun = Instant.now().minusSeconds(1);

            var result = DocTruth.from(provider)
                    .extract("Extract the contract terms", Contract.class)
                    .withProvenance()
                    .withBitemporal()
                    .withSourcePublishedAt(SOURCE_PUBLISHED_AT)
                    .withContextStrategy(priorityStrategy())
                    .run(doc);

            assertThat(result.provenance().sourcePublishedAt()).contains(SOURCE_PUBLISHED_AT);
            assertThat(result.provenance().extractedAt())
                    .isAfter(beforeRun)
                    .isBefore(Instant.now().plusSeconds(1));
            assertThat(result.provenance().extractedAt()).isNotEqualTo(SOURCE_PUBLISHED_AT);
        }
    }

    // --- helpers ---------------------------------------------------------

    /** Wrap a raw JSON string as a JSON-string literal so it can be embedded in a body. */
    private static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void writeComplexPdf(Path path) throws IOException {
        try (var pdf = new PDDocument()) {
            addPage(
                    pdf,
                    List.of(
                            "CONTRACT 2026-005 — Acme Industrial Materials",
                            "Party A: " + PARTY_A,
                            "Party B: " + PARTY_B,
                            "Effective Date: 2026-04-01",
                            "Total Value: AUD 2,450,000"));
            addPage(
                    pdf,
                    List.of(
                            "Qualifications Required",
                            "All bidders must hold ISO 9001 (cert no. 12345),",
                            "ISO 14001 (cert no. 67890), and OHSAS 18001",
                            "(cert no. 24680) at the time of submission."));
            addPage(
                    pdf,
                    List.of(
                            "Scoring Criteria",
                            "1. Price (40%)",
                            "2. Past Performance (30%)",
                            "3. Schedule Compliance (20%)",
                            "4. Innovation (10%)"));
            addPage(
                    pdf,
                    List.of(
                            "Disqualification Conditions",
                            "- Late submission past the closing time",
                            "- Missing official seal on the bid letter",
                            "- Failure to meet mandatory qualifications"));
            pdf.save(path.toFile());
        }
    }

    private static void addPage(PDDocument pdf, List<String> lines) throws IOException {
        var page = new PDPage();
        pdf.addPage(page);
        try (var cs = new PDPageContentStream(pdf, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(50, 720);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    cs.newLineAtOffset(0, -18);
                }
                cs.showText(lines.get(i));
            }
            cs.endText();
        }
    }
}
