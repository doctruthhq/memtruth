package ai.doctruth.internal.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import ai.doctruth.ProviderException;
import ai.doctruth.internal.retry.RetryAfterParser;
import ai.doctruth.internal.retry.RetryableProviderException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin JSON-over-HTTP client used by the four LLM providers. Single responsibility:
 * serialise a request body, send it, deserialise the success body, translate HTTP / IO
 * failures into {@link ProviderException}.
 *
 * <p>NOT public API — see {@code package-info.java}.
 *
 * <p>This client is stateless and safe for concurrent use across virtual threads.
 *
 * @hidden
 */
public final class JsonHttpClient {

    private static final Logger log = LoggerFactory.getLogger(JsonHttpClient.class);

    /** HTTP statuses we mark retryable per ADR 0004 (transient transport / rate-limit). */
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(408, 429, 500, 502, 503, 504);

    /** Cap on bytes of a server-supplied error body propagated into the exception message. */
    private static final int ERROR_BODY_TRUNCATION_CHARS = 500;

    private final ObjectMapper mapper;
    private final HttpClient http;

    /** Production constructor — builds a default JDK {@link HttpClient}. */
    public JsonHttpClient(ObjectMapper mapper) {
        this(mapper, HttpClient.newHttpClient());
    }

    /** Package-private hook for tests / bench harness to inject a pre-configured client. */
    JsonHttpClient(ObjectMapper mapper, HttpClient http) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * POST a JSON body and deserialise the JSON response.
     *
     * @throws ProviderException on any HTTP, network, or JSON-parsing failure. The
     *     exception's {@link ProviderException#retryable() retryable} flag tells the caller
     *     whether {@link ai.doctruth.internal.retry.RetryGate} should retry.
     */
    public <T> T post(
            URI uri,
            Object requestBody,
            Class<T> responseType,
            Map<String, String> headers,
            String providerName,
            Duration timeout)
            throws ProviderException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(requestBody, "requestBody");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(providerName, "providerName");
        Objects.requireNonNull(timeout, "timeout");

        HttpRequest request = buildRequest(uri, requestBody, headers, providerName, timeout);
        log.debug("provider={} method=POST host={} sending request", providerName, uri.getHost());

        HttpResponse<String> response = sendOrTranslate(request, providerName);
        return handleResponse(response, responseType, providerName);
    }

    private HttpRequest buildRequest(
            URI uri, Object requestBody, Map<String, String> headers, String providerName, Duration timeout)
            throws ProviderException {
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(requestBody);
        } catch (JsonProcessingException e) {
            throw new ProviderException(
                    "PROVIDER_REQUEST_INVALID",
                    "failed to serialise request body for provider=" + providerName,
                    providerName,
                    OptionalInt.empty(),
                    false,
                    e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofByteArray(payload));
        headers.forEach(builder::header);
        return builder.build();
    }

    private HttpResponse<String> sendOrTranslate(HttpRequest request, String providerName) throws ProviderException {
        try {
            return http.send(request, BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn(
                    "provider={} transport failure: {}",
                    providerName,
                    e.getClass().getSimpleName());
            throw new ProviderException(
                    "PROVIDER_TRANSPORT_FAILED",
                    "transport failure calling provider=" + providerName + ": " + e.getMessage(),
                    providerName,
                    OptionalInt.empty(),
                    true,
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(
                    "PROVIDER_INTERRUPTED",
                    "interrupted while calling provider=" + providerName,
                    providerName,
                    OptionalInt.empty(),
                    false,
                    e);
        }
    }

    private <T> T handleResponse(HttpResponse<String> response, Class<T> responseType, String providerName)
            throws ProviderException {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return parseSuccessBody(response.body(), responseType, providerName);
        }
        throw httpStatusToException(response, providerName);
    }

    private <T> T parseSuccessBody(String body, Class<T> responseType, String providerName) throws ProviderException {
        try {
            return mapper.readValue(body, responseType);
        } catch (JsonProcessingException e) {
            throw new ProviderException(
                    "PROVIDER_RESPONSE_INVALID",
                    "failed to parse 2xx response from provider=" + providerName + ": " + e.getOriginalMessage(),
                    providerName,
                    OptionalInt.empty(),
                    false,
                    e);
        }
    }

    private ProviderException httpStatusToException(HttpResponse<String> response, String providerName) {
        int status = response.statusCode();
        String body = response.body();
        boolean retryable = RETRYABLE_STATUSES.contains(status);
        String level = retryable ? "retryable" : "non-retryable";
        if (retryable) {
            log.warn("provider={} status={} {} response", providerName, status, level);
        } else {
            log.debug("provider={} status={} {} response", providerName, status, level);
        }
        String code = "PROVIDER_HTTP_" + status;
        String message = "HTTP " + status + " from provider=" + providerName + " body=" + truncate(body);
        if (retryable) {
            Optional<Duration> hint = parseRetryAfter(response, providerName);
            return new RetryableProviderException(code, message, providerName, OptionalInt.of(status), null, hint);
        }
        return new ProviderException(code, message, providerName, OptionalInt.of(status), false);
    }

    private static Optional<Duration> parseRetryAfter(HttpResponse<String> response, String providerName) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isEmpty()) {
            return Optional.empty();
        }
        Optional<Duration> parsed = RetryAfterParser.parse(header.get(), Instant.now());
        if (parsed.isEmpty()) {
            log.debug("provider={} unparseable Retry-After header value=\"{}\"; ignoring", providerName, header.get());
        }
        return parsed;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= ERROR_BODY_TRUNCATION_CHARS) {
            return body;
        }
        return body.substring(0, ERROR_BODY_TRUNCATION_CHARS) + "...(truncated)";
    }
}
