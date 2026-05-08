package ai.doctruth.internal.providers.anthropic.wire;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Anthropic {@code POST /v1/messages} 2xx response body. Snake_case field names mirror the
 * wire JSON; unknown fields are ignored so additive vendor changes do not break callers.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessagesResponse(String id, String model, List<ContentBlock> content, Usage usage) {}
