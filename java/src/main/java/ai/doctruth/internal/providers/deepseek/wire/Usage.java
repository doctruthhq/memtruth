package ai.doctruth.internal.providers.deepseek.wire;

/**
 * Token-usage block returned by DeepSeek on every successful Chat Completions call. Field
 * names mirror the wire shape (snake_case).
 *
 * @hidden
 */
public record Usage(int prompt_tokens, int completion_tokens, int total_tokens) {}
