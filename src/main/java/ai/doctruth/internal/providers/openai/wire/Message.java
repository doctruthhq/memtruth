package ai.doctruth.internal.providers.openai.wire;

/**
 * One element of a Chat Completions {@code messages} array. {@code role} is one of
 * {@code system | user | assistant | tool} per the OpenAI spec; {@code content} is the
 * literal text payload (string content only — multimodal content lands in Phase 2).
 *
 * @hidden
 */
public record Message(String role, String content) {}
