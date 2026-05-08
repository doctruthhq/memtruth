package ai.doctruth.internal.providers.deepseek.wire;

/**
 * One message turn in a DeepSeek Chat Completions exchange.
 *
 * <p>{@code role} is one of {@code "system"}, {@code "user"}, {@code "assistant"} (mirror of
 * the OpenAI Chat Completions spec, which DeepSeek implements verbatim).
 *
 * @hidden
 */
public record Message(String role, String content) {}
