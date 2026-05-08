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
 * WireMock-backed tests for the real Anthropic Messages-API HTTP path through
 * {@link AnthropicProvider}. Per ADR 0003 the Anthropic SDK MUST NOT be on the classpath; this
 * suite exercises the hand-rolled wire records + {@code AnthropicHttpClient} delegation.
 *
 * <p>The provider is constructed with a per-test endpoint pointing at the WireMock server so
 * no real Anthropic traffic is generated; per AGENTS.md "Tests" main-CI uses recorded
 * responses, never live API.
 */
class AnthropicProviderHttpTest {

    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String MODEL = "claude-sonnet-4-5";
    private static final JsonNode SCHEMA = JsonNodeFactory.instance.objectNode();
    private static final ProviderOptions OPTIONS = new ProviderOptions(2, Duration.ofSeconds(2));
    private static final ProviderRequest REQUEST =
            new ProviderRequest("be helpful", "extract revenue", SCHEMA, OPTIONS);

    /** Phase 2 happy-path body: tool_use content block + cache-aware usage counters. */
    private static final String CANNED_BODY = """
            {
              "id": "msg_01ABC",
              "model": "claude-sonnet-4-5-20250929",
              "stop_reason": "tool_use",
              "content": [
                {
                  "type": "tool_use",
                  "id": "toolu_01XYZ",
                  "name": "extract",
                  "input": {"name": "Alex", "age": 30}
                }
              ],
              "usage": {
                "input_tokens": 120,
                "cache_creation_input_tokens": 100,
                "cache_read_input_tokens": 0,
                "output_tokens": 18
              }
            }
            """;

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                    .dynamicPort())
            .build();

    private AnthropicProvider provider() {
        return new AnthropicProvider(
                "test-key", URI.create(wm.getRuntimeInfo().getHttpBaseUrl() + MESSAGES_PATH), MODEL);
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("200 OK: rawJson is the re-serialised tool_use input object")
        void twoHundredHappyPath() throws Exception {
            wm.stubFor(post(urlEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(CANNED_BODY)));

            ProviderResponse response = provider().complete(REQUEST);

            assertThat(response.rawJson()).isEqualTo("{\"name\":\"Alex\",\"age\":30}");
            assertThat(response.usage().inputTokens()).isEqualTo(120);
            assertThat(response.usage().outputTokens()).isEqualTo(18);
            assertThat(response.usage().modelVersion()).isEqualTo("claude-sonnet-4-5-20250929");
        }

        @Test
        @DisplayName("required Anthropic headers (x-api-key, anthropic-version) are sent")
        void requiredHeadersSent() throws Exception {
            wm.stubFor(post(urlEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse().withStatus(200).withBody(CANNED_BODY)));

            provider().complete(REQUEST);

            wm.verify(postRequestedFor(urlEqualTo(MESSAGES_PATH))
                    .withHeader("x-api-key", equalTo("test-key"))
                    .withHeader("anthropic-version", equalTo("2023-06-01"))
                    .withHeader("Content-Type", equalTo("application/json")));
        }

        @Test
        @DisplayName("request body: typed system block with cache_control + tool-use forcing")
        void requestBodyShape() throws Exception {
            wm.stubFor(post(urlEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse().withStatus(200).withBody(CANNED_BODY)));

            provider().complete(REQUEST);

            wm.verify(postRequestedFor(urlEqualTo(MESSAGES_PATH))
                    .withRequestBody(matchingJsonPath("$.model", equalTo(MODEL)))
                    .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("4096")))
                    .withRequestBody(matchingJsonPath("$.system[0].type", equalTo("text")))
                    .withRequestBody(matchingJsonPath("$.system[0].text", equalTo("be helpful")))
                    .withRequestBody(matchingJsonPath("$.system[0].cache_control.type", equalTo("ephemeral")))
                    .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                    .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("extract revenue")))
                    .withRequestBody(matchingJsonPath("$.tools[0].name", equalTo("extract")))
                    .withRequestBody(matchingJsonPath("$.tool_choice.type", equalTo("tool")))
                    .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("extract"))));
        }

        @Test
        @DisplayName("Pydantic-style schema is passed through unchanged for Anthropic tool input_schema")
        void pydanticSchemaPassesThroughForAnthropic() throws Exception {
            wm.stubFor(post(urlEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse().withStatus(200).withBody(CANNED_BODY)));
            var request = new ProviderRequest(
                    "be helpful", "extract profile", ProviderSchemaFixtures.nestedPydanticSchema(), OPTIONS);

            provider().complete(request);

            wm.verify(postRequestedFor(urlEqualTo(MESSAGES_PATH))
                    .withRequestBody(matchingJsonPath("$.tools[0].input_schema.$defs.Address.type", equalTo("object")))
                    .withRequestBody(matchingJsonPath(
                            "$.tools[0].input_schema.properties.address.$ref", equalTo("#/$defs/Address")))
                    .withRequestBody(matchingJsonPath(
                            "$.tools[0].input_schema.properties.nickname.anyOf[1].type", equalTo("null"))));
        }
    }

    @Nested
    @DisplayName("HTTP errors")
    class HttpErrors {

        @Test
        @DisplayName("401 maps to non-retryable PROVIDER_HTTP_401 and fails fast (no retries)")
        void unauthorisedNonRetryable() {
            wm.stubFor(
                    post(urlEqualTo(MESSAGES_PATH))
                            .willReturn(
                                    aResponse()
                                            .withStatus(401)
                                            .withBody(
                                                    "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}")));

            assertThatThrownBy(() -> provider().complete(REQUEST))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_HTTP_401");
                        assertThat(ex.retryable()).isFalse();
                        assertThat(ex.httpStatus()).hasValue(401);
                        assertThat(ex.providerName()).isEqualTo("anthropic");
                    });

            wm.verify(1, postRequestedFor(urlEqualTo(MESSAGES_PATH)));
        }
    }

    @Nested
    @DisplayName("Retry")
    class Retry {

        @Test
        @DisplayName("429 then 200 → retry succeeds and returns the second-response payload")
        void rateLimitThenSuccess() throws Exception {
            String scenarioName = "anthropic-retry-429";
            wm.stubFor(post(urlEqualTo(MESSAGES_PATH))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(429).withBody("rate limited"))
                    .willSetStateTo("retried"));
            wm.stubFor(post(urlEqualTo(MESSAGES_PATH))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs("retried")
                    .willReturn(aResponse().withStatus(200).withBody(CANNED_BODY)));

            ProviderResponse response = provider().complete(REQUEST);

            assertThat(response.rawJson()).isEqualTo("{\"name\":\"Alex\",\"age\":30}");
            wm.verify(2, postRequestedFor(urlEqualTo(MESSAGES_PATH)));
        }
    }

    @Nested
    @DisplayName("Response validation")
    class ResponseValidation {

        @Test
        @DisplayName("empty content array → PROVIDER_RESPONSE_INVALID, non-retryable")
        void emptyContentArray() {
            String emptyContentBody = """
                    {
                      "id": "msg_01ABC",
                      "model": "claude-sonnet-4-5-20250929",
                      "content": [],
                      "usage": {"input_tokens": 1, "output_tokens": 0}
                    }
                    """;
            wm.stubFor(post(urlEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse().withStatus(200).withBody(emptyContentBody)));

            assertThatThrownBy(() -> provider().complete(REQUEST))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_RESPONSE_INVALID");
                        assertThat(ex.retryable()).isFalse();
                        assertThat(ex.providerName()).isEqualTo("anthropic");
                    });
        }

        @Test
        @DisplayName("response with type != tool_use throws PROVIDER_RESPONSE_INVALID")
        void contentTypeNotToolUse() {
            String textTypeBody = """
                    {
                      "id": "msg_01ABC",
                      "model": "claude-sonnet-4-5-20250929",
                      "content": [{"type": "text", "text": "{\\"revenue\\":42}"}],
                      "usage": {"input_tokens": 1, "output_tokens": 0}
                    }
                    """;
            wm.stubFor(post(urlEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse().withStatus(200).withBody(textTypeBody)));

            assertThatThrownBy(() -> provider().complete(REQUEST))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_RESPONSE_INVALID");
                        assertThat(ex.retryable()).isFalse();
                        assertThat(ex.providerName()).isEqualTo("anthropic");
                    });
        }

        @Test
        @DisplayName("response with tool_use name != 'extract' throws PROVIDER_RESPONSE_INVALID")
        void toolUseNameMismatch() {
            String wrongToolBody = """
                    {
                      "id": "msg_01ABC",
                      "model": "claude-sonnet-4-5-20250929",
                      "content": [
                        {
                          "type": "tool_use",
                          "id": "toolu_01ZZZ",
                          "name": "some_other_tool",
                          "input": {"foo": "bar"}
                        }
                      ],
                      "usage": {"input_tokens": 1, "output_tokens": 0}
                    }
                    """;
            wm.stubFor(post(urlEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse().withStatus(200).withBody(wrongToolBody)));

            assertThatThrownBy(() -> provider().complete(REQUEST))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_RESPONSE_INVALID");
                        assertThat(ex.retryable()).isFalse();
                    });
        }

        @Test
        @DisplayName("usage.cache_read_input_tokens is parsed when present (non-zero)")
        void cacheReadTokensParsed() throws Exception {
            String cacheHitBody = """
                    {
                      "id": "msg_01ABC",
                      "model": "claude-sonnet-4-5-20250929",
                      "content": [
                        {
                          "type": "tool_use",
                          "id": "toolu_01XYZ",
                          "name": "extract",
                          "input": {"name": "Alex"}
                        }
                      ],
                      "usage": {
                        "input_tokens": 20,
                        "cache_creation_input_tokens": 0,
                        "cache_read_input_tokens": 100,
                        "output_tokens": 18
                      }
                    }
                    """;
            wm.stubFor(post(urlEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse().withStatus(200).withBody(cacheHitBody)));

            ProviderResponse response = provider().complete(REQUEST);

            assertThat(response.rawJson()).isEqualTo("{\"name\":\"Alex\"}");
            assertThat(response.usage().inputTokens()).isEqualTo(20);
            assertThat(response.usage().outputTokens()).isEqualTo(18);
        }

        @Test
        @DisplayName("usage without cache_* fields parses cleanly (cache_creation/read default to 0)")
        void usageWithoutCacheFields() throws Exception {
            String legacyUsageBody = """
                    {
                      "id": "msg_01ABC",
                      "model": "claude-sonnet-4-5-20250929",
                      "content": [
                        {
                          "type": "tool_use",
                          "id": "toolu_01XYZ",
                          "name": "extract",
                          "input": {"revenue": 42}
                        }
                      ],
                      "usage": {"input_tokens": 50, "output_tokens": 10}
                    }
                    """;
            wm.stubFor(post(urlEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse().withStatus(200).withBody(legacyUsageBody)));

            ProviderResponse response = provider().complete(REQUEST);

            assertThat(response.rawJson()).isEqualTo("{\"revenue\":42}");
            assertThat(response.usage().inputTokens()).isEqualTo(50);
            assertThat(response.usage().outputTokens()).isEqualTo(10);
        }
    }
}
