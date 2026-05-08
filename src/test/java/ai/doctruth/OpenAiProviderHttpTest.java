package ai.doctruth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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
 * WireMock-backed contract tests for {@link OpenAiProvider}'s HTTP backend. Validates the
 * Chat Completions request shape, header set, response parsing, retry behaviour, and
 * defensive checks for malformed responses.
 */
class OpenAiProviderHttpTest {

    private static final String API_KEY = "sk-test-abc";
    private static final String MODEL = "gpt-4o";
    private static final String CHAT_PATH = "/v1/chat/completions";

    private static final JsonNode SCHEMA = JsonNodeFactory.instance.objectNode().put("type", "object");
    private static final ProviderOptions OPTIONS = new ProviderOptions(0, Duration.ofSeconds(2));
    private static final ProviderOptions OPTIONS_WITH_RETRY = new ProviderOptions(2, Duration.ofSeconds(2));
    private static final ProviderRequest REQUEST =
            new ProviderRequest("you are an extraction agent", "extract names from this", SCHEMA, OPTIONS);

    private static final String CANNED_RAW_JSON = "{\"name\":\"Alex\"}";
    // The "content" field embeds the assistant's raw JSON as a string, so we have to
    // re-escape the inner quotes when it's the value of an enclosing JSON field.
    private static final String CANNED_RESPONSE = """
            {
              "id": "chatcmpl-abc",
              "model": "gpt-4o-2024-08-06",
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "{\\"name\\":\\"Alex\\"}"},
                  "finish_reason": "stop"
                }
              ],
              "usage": {"prompt_tokens": 120, "completion_tokens": 18, "total_tokens": 138}
            }
            """;

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                    .dynamicPort())
            .build();

    private OpenAiProvider provider() {
        return new OpenAiProvider(API_KEY, URI.create(wm.getRuntimeInfo().getHttpBaseUrl() + CHAT_PATH), MODEL);
    }

    @Nested
    @DisplayName("HappyPath")
    class HappyPath {

        @Test
        @DisplayName("200 OK extracts choices[0].message.content as rawJson and usage tokens match")
        void twoHundredHappyPath() throws Exception {
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_RESPONSE)));

            ProviderResponse response = provider().complete(REQUEST);

            assertThat(response.rawJson()).isEqualTo(CANNED_RAW_JSON);
            assertThat(response.usage().inputTokens()).isEqualTo(120);
            assertThat(response.usage().outputTokens()).isEqualTo(18);
            assertThat(response.usage().modelVersion()).isEqualTo("gpt-4o-2024-08-06");
        }

        @Test
        @DisplayName("Authorization header is sent as \"Bearer <apiKey>\"")
        void authorizationHeaderSent() throws Exception {
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_RESPONSE)));

            provider().complete(REQUEST);

            wm.verify(
                    postRequestedFor(urlEqualTo(CHAT_PATH)).withHeader("Authorization", equalTo("Bearer " + API_KEY)));
        }

        @Test
        @DisplayName("request body has model, messages, and native json_schema response_format")
        void requestBodyShape() throws Exception {
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_RESPONSE)));

            provider().complete(REQUEST);

            wm.verify(postRequestedFor(urlEqualTo(CHAT_PATH))
                    .withRequestBody(matchingJsonPath("$.model", equalTo(MODEL)))
                    .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                    .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("you are an extraction agent")))
                    .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user")))
                    .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("extract names from this")))
                    .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_schema")))
                    .withRequestBody(
                            matchingJsonPath("$.response_format.json_schema.name", equalTo("doctruth_extract")))
                    .withRequestBody(matchingJsonPath("$.response_format.json_schema.strict", equalTo("true")))
                    .withRequestBody(matchingJsonPath("$.response_format.json_schema.schema.type", equalTo("object"))));
        }
    }

    @Nested
    @DisplayName("HttpErrors")
    class HttpErrors {

        @Test
        @DisplayName("401 → ProviderException PROVIDER_HTTP_401, retryable=false")
        void unauthorisedNonRetryable() {
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"bad key\"}")));

            assertThatThrownBy(() -> provider().complete(REQUEST))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_HTTP_401");
                        assertThat(ex.retryable()).isFalse();
                        assertThat(ex.providerName()).isEqualTo("openai");
                        assertThat(ex.httpStatus()).hasValue(401);
                    });
        }
    }

    @Nested
    @DisplayName("Retry")
    class Retry {

        @Test
        @DisplayName("429 then 200 succeeds via RetryGate (maxRetries=2)")
        void retriesOn429() throws Exception {
            String scenario = "openai-retry";
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .inScenario(scenario)
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(429).withBody("rate limited"))
                    .willSetStateTo("after-429"));
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .inScenario(scenario)
                    .whenScenarioStateIs("after-429")
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_RESPONSE)));

            ProviderRequest retryReq =
                    new ProviderRequest(REQUEST.systemPrompt(), REQUEST.userPrompt(), SCHEMA, OPTIONS_WITH_RETRY);

            ProviderResponse response = provider().complete(retryReq);

            assertThat(response.rawJson()).isEqualTo(CANNED_RAW_JSON);
            wm.verify(2, postRequestedFor(urlEqualTo(CHAT_PATH)));
        }
    }

    @Nested
    @DisplayName("ResponseValidation")
    class ResponseValidation {

        @Test
        @DisplayName("empty choices array → ProviderException PROVIDER_RESPONSE_INVALID")
        void emptyChoicesArray() {
            String emptyChoices = """
                    {
                      "id": "chatcmpl-empty",
                      "model": "gpt-4o-2024-08-06",
                      "choices": [],
                      "usage": {"prompt_tokens": 1, "completion_tokens": 0, "total_tokens": 1}
                    }
                    """;
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(emptyChoices)));

            assertThatThrownBy(() -> provider().complete(REQUEST))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_RESPONSE_INVALID");
                        assertThat(ex.retryable()).isFalse();
                        assertThat(ex.providerName()).isEqualTo("openai");
                    });
        }

        @Test
        @DisplayName("blank message content → ProviderException PROVIDER_RESPONSE_INVALID")
        void blankMessageContent() {
            String blankContent = """
                    {
                      "id": "chatcmpl-blank",
                      "model": "gpt-4o-2024-08-06",
                      "choices": [
                        {"index": 0, "message": {"role": "assistant", "content": ""}, "finish_reason": "stop"}
                      ],
                      "usage": {"prompt_tokens": 1, "completion_tokens": 0, "total_tokens": 1}
                    }
                    """;
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(blankContent)));

            assertThatThrownBy(() -> provider().complete(REQUEST))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_RESPONSE_INVALID");
                        assertThat(ex.retryable()).isFalse();
                    });
        }
    }
}
