package ai.doctruth.internal.providers.deepseek.wire;

/**
 * One choice in a DeepSeek Chat Completions response. Field names mirror the wire shape
 * (snake_case).
 *
 * @hidden
 */
public record Choice(int index, Message message, String finish_reason) {}
