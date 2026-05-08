package ai.doctruth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * WireMock-backed contract tests for {@link GeminiProvider} HTTP behaviour. Asserts that
 * the production provider talks to the Gemini {@code generateContent} endpoint with the
 * documented request shape, surfaces vendor errors as {@link ProviderException} with the
 * stable error codes, and applies retries per ADR 0004.
 *
 * <p>The model identifier appears in the URL path ({@code /v1beta/models/{model}:generateContent}),
 * not the request body — match Google's wire format exactly.
 */
class GeminiProviderHttpTest {

    private static final String MODEL = "gemini-1.5-pro";
    private static final String PATH = "/v1beta/models/" + MODEL + ":generateContent";
    private static final JsonNode SCHEMA = JsonNodeFactory.instance.objectNode().put("type", "object");
    private static final ProviderOptions OPTIONS = new ProviderOptions(2, Duration.ofSeconds(2));
    private static final ProviderRequest REQUEST =
            new ProviderRequest("you are precise", "extract the total", SCHEMA, OPTIONS);

    private static final String CANNED_OK = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [{"text": "{\\"total\\":42}"}]
                  },
                  "finishReason": "STOP",
                  "index": 0
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 120,
                "candidatesTokenCount": 18,
                "totalTokenCount": 138
              },
              "modelVersion": "gemini-1.5-pro-002"
            }
            """;

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                    .dynamicPort())
            .build();

    private GeminiProvider providerForBaseUrl() {
        URI base = URI.create(wm.getRuntimeInfo().getHttpBaseUrl());
        return new GeminiProvider("test-key", base, MODEL);
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("200 OK extracts rawJson + usage + modelVersion")
        void twoHundredHappyPath() throws Exception {
            wm.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_OK)));

            var response = providerForBaseUrl().complete(REQUEST);

            assertThat(response.rawJson()).isEqualTo("{\"total\":42}");
            assertThat(response.usage().inputTokens()).isEqualTo(120);
            assertThat(response.usage().outputTokens()).isEqualTo(18);
            assertThat(response.usage().modelVersion()).isEqualTo("gemini-1.5-pro-002");
        }

        @Test
        @DisplayName("x-goog-api-key header is sent and key NOT in URL")
        void apiKeyHeaderSent() throws Exception {
            wm.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_OK)));

            providerForBaseUrl().complete(REQUEST);

            wm.verify(postRequestedFor(urlEqualTo(PATH))
                    .withHeader("x-goog-api-key", equalTo("test-key"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withQueryParam("key", absent()));
        }

        @Test
        @DisplayName("request body carries system_instruction, user content, and native JSON schema generationConfig")
        void requestBodyShape() throws Exception {
            wm.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_OK)));

            providerForBaseUrl().complete(REQUEST);

            wm.verify(postRequestedFor(urlPathEqualTo(PATH))
                    .withRequestBody(matchingJsonPath("$.system_instruction.parts[0].text", equalTo("you are precise")))
                    .withRequestBody(matchingJsonPath("$.contents[0].role", equalTo("user")))
                    .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text", equalTo("extract the total")))
                    .withRequestBody(
                            matchingJsonPath("$.generationConfig.responseMimeType", equalTo("application/json")))
                    .withRequestBody(matchingJsonPath("$.generationConfig.responseSchema.type", equalTo("object"))));
        }
    }

    @Nested
    @DisplayName("HTTP errors")
    class HttpErrors {

        @Test
        @DisplayName("401 maps to non-retryable PROVIDER_HTTP_401")
        void unauthorisedNonRetryable() {
            wm.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"bad key\"}")));

            assertThatThrownBy(() -> providerForBaseUrl().complete(REQUEST))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_HTTP_401");
                        assertThat(ex.retryable()).isFalse();
                        assertThat(ex.httpStatus()).hasValue(401);
                        assertThat(ex.providerName()).isEqualTo("gemini");
                    });
        }
    }

    @Nested
    @DisplayName("Retry")
    class Retry {

        @Test
        @DisplayName("429 then 200 retries successfully")
        void rateLimitedThenSucceeds() throws Exception {
            String scenario = "rateLimit";
            wm.stubFor(post(urlEqualTo(PATH))
                    .inScenario(scenario)
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(429).withBody("rate limited"))
                    .willSetStateTo("after-429"));
            wm.stubFor(post(urlEqualTo(PATH))
                    .inScenario(scenario)
                    .whenScenarioStateIs("after-429")
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_OK)));

            var response = providerForBaseUrl().complete(REQUEST);

            assertThat(response.rawJson()).isEqualTo("{\"total\":42}");
            assertThat(response.usage().modelVersion()).isEqualTo("gemini-1.5-pro-002");
        }
    }

    @Nested
    @DisplayName("Response validation")
    class ResponseValidation {

        @Test
        @DisplayName("empty candidates → PROVIDER_RESPONSE_INVALID")
        void emptyCandidates() {
            wm.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "candidates": [],
                                      "usageMetadata": {
                                        "promptTokenCount": 1,
                                        "candidatesTokenCount": 0,
                                        "totalTokenCount": 1
                                      },
                                      "modelVersion": "gemini-1.5-pro-002"
                                    }
                                    """)));

            assertThatThrownBy(() -> providerForBaseUrl().complete(REQUEST))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_RESPONSE_INVALID");
                        assertThat(ex.retryable()).isFalse();
                        assertThat(ex.providerName()).isEqualTo("gemini");
                    });
        }
    }
}
