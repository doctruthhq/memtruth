package ai.doctruth.internal.providers.deepseek.wire;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Response body returned by {@code POST /v1/chat/completions} on DeepSeek. We pin only the
 * fields we use; {@link JsonIgnoreProperties} tolerates new fields shipped by DeepSeek
 * (e.g. {@code reasoning_content} on R1) without breaking parse.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionsResponse(String id, String model, List<Choice> choices, Usage usage) {}
