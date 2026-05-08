package ai.doctruth.internal.providers.openai.wire;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OpenAI {@code POST /v1/chat/completions} 2xx response body. Snake_case field names
 * mirror the wire JSON; unknown fields are ignored so additive vendor changes do not
 * break callers.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionsResponse(String id, String model, List<Choice> choices, Usage usage) {}
