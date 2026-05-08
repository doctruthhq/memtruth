package ai.doctruth.internal.providers.openai.wire;

import java.util.List;

/**
 * OpenAI {@code POST /v1/chat/completions} request body. Snake_case field names mirror the
 * wire JSON exactly so Jackson serialises without custom configuration.
 *
 * @hidden
 */
public record ChatCompletionsRequest(String model, List<Message> messages, ResponseFormat response_format) {}
