package ai.doctruth.internal.providers.openai.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Per-call token-usage summary from a Chat Completions response. Snake_case mirrors the
 * wire field names.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Usage(int prompt_tokens, int completion_tokens, int total_tokens) {}
