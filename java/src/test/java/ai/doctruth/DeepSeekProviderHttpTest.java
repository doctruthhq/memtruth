package ai.doctruth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * WireMock-backed contract tests for {@link DeepSeekProvider}. Exercises the wire shape
 * (Authorization header, JSON body, {@code response_format=json_object}), HTTP error
 * handling, retry behaviour, and response validation.
 */
class DeepSeekProviderHttpTest {

    private static final String API_KEY = "ds-test-key";
    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final JsonNode SCHEMA = JsonNodeFactory.instance.objectNode();
    private static final ProviderOptions OPTIONS_NO_RETRY = new ProviderOptions(0, Duration.ofSeconds(2));
    private static final ProviderOptions OPTIONS_ONE_RETRY = new ProviderOptions(1, Duration.ofSeconds(2));

    private static final String CANNED_RAW_JSON = "{\"answer\":\"42\",\"confidence\":0.9}";
    private static final String CANNED_RESPONSE_BODY = """
            {
              "id": "chatcmpl-ds-1",
              "model": "deepseek-chat-2026-05-01",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "%s"},
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 120, "completion_tokens": 18, "total_tokens": 138}
            }
            """.formatted(CANNED_RAW_JSON.replace("\"", "\\\""));

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                    .dynamicPort())
            .build();

    private DeepSeekProvider providerAt(String path, ProviderOptions ignored) {
        return new DeepSeekProvider(API_KEY, URI.create(wm.getRuntimeInfo().getHttpBaseUrl() + path), "deepseek-chat");
    }

    private static ProviderRequest request(ProviderOptions options) {
        return new ProviderRequest("you are a helpful assistant", "what is 6 times 7?", SCHEMA, options);
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("200 OK returns rawJson + usage + modelVersion from the response body")
        void happyPath() throws Exception {
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_RESPONSE_BODY)));

            var provider = providerAt(CHAT_PATH, OPTIONS_NO_RETRY);

            var response = provider.complete(request(OPTIONS_NO_RETRY));

            assertThat(response.rawJson()).isEqualTo(CANNED_RAW_JSON);
            assertThat(response.usage().inputTokens()).isEqualTo(120);
            assertThat(response.usage().outputTokens()).isEqualTo(18);
            assertThat(response.usage().modelVersion()).isEqualTo("deepseek-chat-2026-05-01");
        }

        @Test
        @DisplayName("Authorization: Bearer <apiKey> header is present on every request")
        void authorizationHeaderSent() throws Exception {
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_RESPONSE_BODY)));

            var provider = providerAt(CHAT_PATH, OPTIONS_NO_RETRY);

            assertThat(provider.complete(request(OPTIONS_NO_RETRY)).rawJson()).isEqualTo(CANNED_RAW_JSON);
        }

        @Test
        @DisplayName("request body has model, system+user messages, and json_object response_format")
        void requestBodyShape() throws Exception {
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .withRequestBody(matchingJsonPath("$.model", equalTo("deepseek-chat")))
                    .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                    .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("you are a helpful assistant")))
                    .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user")))
                    .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("what is 6 times 7?")))
                    .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object")))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_RESPONSE_BODY)));

            var provider = providerAt(CHAT_PATH, OPTIONS_NO_RETRY);

            assertThat(provider.complete(request(OPTIONS_NO_RETRY)).rawJson()).isEqualTo(CANNED_RAW_JSON);
        }
    }

    @Nested
    @DisplayName("HTTP errors")
    class HttpErrors {

        @Test
        @DisplayName("401 surfaces as non-retryable PROVIDER_HTTP_401")
        void unauthorisedNonRetryable() {
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"bad key\"}")));

            var provider = providerAt(CHAT_PATH, OPTIONS_ONE_RETRY);

            assertThatThrownBy(() -> provider.complete(request(OPTIONS_ONE_RETRY)))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_HTTP_401");
                        assertThat(ex.retryable()).isFalse();
                        assertThat(ex.httpStatus()).hasValue(401);
                        assertThat(ex.providerName()).isEqualTo("deepseek");
                    });
        }
    }

    @Nested
    @DisplayName("Retry")
    class Retry {

        @Test
        @DisplayName("429 then 200 succeeds when maxRetries=1")
        void retryOnRateLimitThenSuccess() throws Exception {
            String scenario = "ds-rate-limit";
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .inScenario(scenario)
                    .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                    .willReturn(aResponse().withStatus(429).withBody("rate limited"))
                    .willSetStateTo("after-429"));
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .inScenario(scenario)
                    .whenScenarioStateIs("after-429")
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_RESPONSE_BODY)));

            var provider = providerAt(CHAT_PATH, OPTIONS_ONE_RETRY);

            assertThat(provider.complete(request(OPTIONS_ONE_RETRY)).rawJson()).isEqualTo(CANNED_RAW_JSON);
        }
    }

    @Nested
    @DisplayName("Response validation")
    class ResponseValidation {

        @Test
        @DisplayName("200 with empty choices array → PROVIDER_RESPONSE_INVALID, non-retryable")
        void emptyChoices() {
            wm.stubFor(post(urlEqualTo(CHAT_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"id\":\"x\",\"model\":\"deepseek-chat\",\"choices\":[],"
                                    + "\"usage\":{\"prompt_tokens\":1,"
                                    + "\"completion_tokens\":0,\"total_tokens\":1}}")));

            var provider = providerAt(CHAT_PATH, OPTIONS_NO_RETRY);

            assertThatThrownBy(() -> provider.complete(request(OPTIONS_NO_RETRY)))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_RESPONSE_INVALID");
                        assertThat(ex.retryable()).isFalse();
                        assertThat(ex.providerName()).isEqualTo("deepseek");
                    });
        }
    }
}
