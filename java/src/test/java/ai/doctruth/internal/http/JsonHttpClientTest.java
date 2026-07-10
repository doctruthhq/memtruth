package ai.doctruth.internal.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import ai.doctruth.ProviderException;
import ai.doctruth.internal.retry.RetryableProviderException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Contract tests for {@link JsonHttpClient}.
 *
 * <p>WireMock is registered per-class (not per-test) because each test points at a unique
 * URL path, the server is stateless, and a per-class instance keeps the suite under 1
 * second total. Per-test isolation is unnecessary and would triple wall-clock time.
 */
class JsonHttpClientTest {

    private static final String PROVIDER = "anthropic";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Map<String, String> AUTH_HEADER = Map.of("X-Api-Key", "test-key");

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                    .dynamicPort())
            .build();

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonHttpClient client = new JsonHttpClient(mapper);

    record SampleRequest(String prompt) {}

    record SampleBody(boolean ok, String message) {}

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("200 OK deserialises body into the target record")
        void twoHundredHappyPath() throws Exception {
            wm.stubFor(post(urlEqualTo("/messages"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withHeader("X-Api-Key", equalTo("test-key"))
                    .withRequestBody(equalToJson("{\"prompt\":\"hi\"}"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"ok\":true,\"message\":\"done\"}")));

            var result = client.post(
                    URI.create(wm.getRuntimeInfo().getHttpBaseUrl() + "/messages"),
                    new SampleRequest("hi"),
                    SampleBody.class,
                    AUTH_HEADER,
                    PROVIDER,
                    TIMEOUT);

            assertThat(result.ok()).isTrue();
            assertThat(result.message()).isEqualTo("done");
        }
    }

    @Nested
    @DisplayName("HTTP errors")
    class HttpErrors {

        @Test
        @DisplayName("401 throws non-retryable ProviderException with code PROVIDER_HTTP_401")
        void unauthorisedNonRetryable() {
            wm.stubFor(post(urlEqualTo("/h401"))
                    .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"bad key\"}")));

            assertThatThrownBy(() -> postTo("/h401")).isInstanceOfSatisfying(ProviderException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo("PROVIDER_HTTP_401");
                assertThat(ex.retryable()).isFalse();
                assertThat(ex.httpStatus()).hasValue(401);
                assertThat(ex.providerName()).isEqualTo(PROVIDER);
                assertThat(ex.getMessage()).contains("bad key");
            });
        }

        @Test
        @DisplayName("429 throws retryable ProviderException with code PROVIDER_HTTP_429")
        void tooManyRequestsRetryable() {
            wm.stubFor(post(urlEqualTo("/h429"))
                    .willReturn(aResponse().withStatus(429).withBody("rate limited")));

            assertThatThrownBy(() -> postTo("/h429")).isInstanceOfSatisfying(ProviderException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo("PROVIDER_HTTP_429");
                assertThat(ex.retryable()).isTrue();
                assertThat(ex.httpStatus()).hasValue(429);
            });
        }

        @Test
        @DisplayName("500 throws retryable ProviderException")
        void internalServerErrorRetryable() {
            wm.stubFor(post(urlEqualTo("/h500"))
                    .willReturn(aResponse().withStatus(500).withBody("boom")));

            assertThatThrownBy(() -> postTo("/h500")).isInstanceOfSatisfying(ProviderException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo("PROVIDER_HTTP_500");
                assertThat(ex.retryable()).isTrue();
                assertThat(ex.httpStatus()).hasValue(500);
            });
        }

        @Test
        @DisplayName("503 throws retryable ProviderException")
        void serviceUnavailableRetryable() {
            wm.stubFor(post(urlEqualTo("/h503"))
                    .willReturn(aResponse().withStatus(503).withBody("down")));

            assertThatThrownBy(() -> postTo("/h503")).isInstanceOfSatisfying(ProviderException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo("PROVIDER_HTTP_503");
                assertThat(ex.retryable()).isTrue();
            });
        }

        @Test
        @DisplayName("400 throws non-retryable ProviderException")
        void badRequestNonRetryable() {
            wm.stubFor(post(urlEqualTo("/h400"))
                    .willReturn(aResponse().withStatus(400).withBody("malformed")));

            assertThatThrownBy(() -> postTo("/h400")).isInstanceOfSatisfying(ProviderException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo("PROVIDER_HTTP_400");
                assertThat(ex.retryable()).isFalse();
            });
        }

        @Test
        @DisplayName("oversized error body is truncated in ProviderException.message")
        void oversizedBodyTruncated() {
            String hugeBody = "x".repeat(5000);
            wm.stubFor(post(urlEqualTo("/huge"))
                    .willReturn(aResponse().withStatus(500).withBody(hugeBody)));

            assertThatThrownBy(() -> postTo("/huge")).isInstanceOfSatisfying(ProviderException.class, ex -> {
                // Truncation budget is 500 chars + a small prefix ("HTTP 500 ... body=")
                // — well under 1000 total.
                assertThat(ex.getMessage().length()).isLessThan(1000);
                // It still must contain at least one body-character so we know the
                // body wasn't wholly dropped.
                assertThat(ex.getMessage()).contains("x");
            });
        }
    }

    @Nested
    @DisplayName("Network")
    class Network {

        @Test
        @DisplayName("connection refused → PROVIDER_TRANSPORT_FAILED, retryable=true, no httpStatus")
        void connectionRefused() throws Exception {
            int unusedPort;
            // Bind a server socket transiently to discover an unused port, then release it
            // so the connect attempt fails fast with ECONNREFUSED.
            try (var probe = new ServerSocket(0)) {
                unusedPort = probe.getLocalPort();
            }
            URI dead = URI.create("http://127.0.0.1:" + unusedPort + "/never");

            assertThatThrownBy(() ->
                            client.post(dead, new SampleRequest("x"), SampleBody.class, AUTH_HEADER, PROVIDER, TIMEOUT))
                    .isInstanceOfSatisfying(ProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_TRANSPORT_FAILED");
                        assertThat(ex.retryable()).isTrue();
                        assertThat(ex.httpStatus()).isEmpty();
                        assertThat(ex.getCause()).isNotNull();
                    });
        }
    }

    @Nested
    @DisplayName("Response parsing")
    class ResponseParsing {

        @Test
        @DisplayName("malformed 2xx body → PROVIDER_RESPONSE_INVALID, non-retryable")
        void malformedResponseBody() {
            wm.stubFor(post(urlEqualTo("/garbled"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("not-json-at-all")));

            assertThatThrownBy(() -> postTo("/garbled")).isInstanceOfSatisfying(ProviderException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo("PROVIDER_RESPONSE_INVALID");
                assertThat(ex.retryable()).isFalse();
                // 2xx responses never carry an HTTP error status — empty.
                assertThat(ex.httpStatus()).isEmpty();
            });
        }
    }

    @Nested
    @DisplayName("Retry-After header (RFC 7231 §7.1.3)")
    class RetryAfter {

        @Test
        @DisplayName("429 + \"Retry-After: 5\" → RetryableProviderException with 5s hint")
        void rateLimitDeltaSeconds() {
            wm.stubFor(post(urlEqualTo("/ra-429-secs"))
                    .willReturn(aResponse()
                            .withStatus(429)
                            .withHeader("Retry-After", "5")
                            .withBody("slow down")));

            assertThatThrownBy(() -> postTo("/ra-429-secs"))
                    .isInstanceOfSatisfying(RetryableProviderException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("PROVIDER_HTTP_429");
                        assertThat(ex.retryable()).isTrue();
                        assertThat(ex.minRetryDelay()).contains(Duration.ofSeconds(5));
                    });
        }

        @Test
        @DisplayName("503 + future HTTP-date → RetryableProviderException with positive hint")
        void unavailableHttpDate() {
            String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(30));
            wm.stubFor(post(urlEqualTo("/ra-503-date"))
                    .willReturn(aResponse()
                            .withStatus(503)
                            .withHeader("Retry-After", header)
                            .withBody("down")));

            assertThatThrownBy(() -> postTo("/ra-503-date"))
                    .isInstanceOfSatisfying(RetryableProviderException.class, ex -> {
                        assertThat(ex.retryable()).isTrue();
                        assertThat(ex.minRetryDelay()).isPresent();
                        assertThat(ex.minRetryDelay().get()).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(60));
                    });
        }

        @Test
        @DisplayName("429 without Retry-After → RetryableProviderException with empty hint")
        void rateLimitNoHeader() {
            wm.stubFor(post(urlEqualTo("/ra-429-bare"))
                    .willReturn(aResponse().withStatus(429).withBody("rate limited")));

            assertThatThrownBy(() -> postTo("/ra-429-bare"))
                    .isInstanceOfSatisfying(RetryableProviderException.class, ex -> {
                        assertThat(ex.retryable()).isTrue();
                        assertThat(ex.minRetryDelay()).isEmpty();
                    });
        }

        @Test
        @DisplayName("401 + Retry-After (server is rude) → plain ProviderException, not retryable")
        void unauthorisedIgnoresRetryAfter() {
            wm.stubFor(post(urlEqualTo("/ra-401"))
                    .willReturn(aResponse()
                            .withStatus(401)
                            .withHeader("Retry-After", "30")
                            .withBody("nope")));

            assertThatThrownBy(() -> postTo("/ra-401")).isInstanceOfSatisfying(ProviderException.class, ex -> {
                assertThat(ex.retryable()).isFalse();
                // Must NOT be wrapped as RetryableProviderException — 401 is non-retryable
                // regardless of whether the server suggests a delay.
                assertThat(ex).isNotInstanceOf(RetryableProviderException.class);
            });
        }

        @Test
        @DisplayName("429 + unparseable Retry-After → RetryableProviderException with empty hint")
        void rateLimitGarbageHeader() {
            wm.stubFor(post(urlEqualTo("/ra-429-garbage"))
                    .willReturn(aResponse()
                            .withStatus(429)
                            .withHeader("Retry-After", "not-a-real-value")
                            .withBody("rate limited")));

            assertThatThrownBy(() -> postTo("/ra-429-garbage"))
                    .isInstanceOfSatisfying(RetryableProviderException.class, ex -> {
                        assertThat(ex.retryable()).isTrue();
                        assertThat(ex.minRetryDelay()).isEmpty();
                    });
        }
    }

    private SampleBody postTo(String path) throws ProviderException {
        return client.post(
                URI.create(wm.getRuntimeInfo().getHttpBaseUrl() + path),
                new SampleRequest("x"),
                SampleBody.class,
                AUTH_HEADER,
                PROVIDER,
                TIMEOUT);
    }
}
