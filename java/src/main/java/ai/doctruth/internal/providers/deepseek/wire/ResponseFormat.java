package ai.doctruth.internal.providers.deepseek.wire;

/**
 * DeepSeek {@code response_format} switch. Pass {@code "json_object"} to force JSON mode.
 *
 * @hidden
 */
public record ResponseFormat(String type) {}
