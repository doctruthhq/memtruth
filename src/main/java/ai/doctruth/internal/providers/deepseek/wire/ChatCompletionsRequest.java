package ai.doctruth.internal.providers.deepseek.wire;

import java.util.List;

/**
 * Request body for {@code POST /v1/chat/completions} on DeepSeek. Field names are wire-
 * snake_case so Jackson serialises them verbatim.
 *
 * @hidden
 */
public record ChatCompletionsRequest(String model, List<Message> messages, ResponseFormat response_format) {}
