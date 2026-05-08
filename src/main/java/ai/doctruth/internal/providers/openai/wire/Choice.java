package ai.doctruth.internal.providers.openai.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One element of a Chat Completions response {@code choices} array. {@code finish_reason}
 * snake_case mirrors the wire field. Unknown fields are ignored so additive vendor
 * changes do not break callers.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Choice(int index, Message message, String finish_reason) {}
