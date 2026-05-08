package ai.doctruth.internal.providers.anthropic.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One element of {@link MessagesResponse#content()}. Phase 2 happy path returns
 * {@code type:"tool_use"} with a populated {@code name} + {@code input} (the parsed JSON
 * object Anthropic produced for the forced {@code "extract"} tool). The legacy
 * {@code type:"text"} shape is still recognised so old recorded fixtures and non-tool-use
 * fallbacks continue to deserialise; callers branch on {@code type} and ignore the unused
 * field.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentBlock(String type, String text, String name, JsonNode input) {}
