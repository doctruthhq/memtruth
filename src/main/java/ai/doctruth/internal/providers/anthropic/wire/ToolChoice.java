package ai.doctruth.internal.providers.anthropic.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Forces the model to use a specific declared {@link Tool} on the next response. The library
 * always sends {@code {type:"tool", name:"extract"}} so Anthropic returns a single
 * {@code tool_use} content block whose {@code input} is the parsed JSON object — no fallible
 * "extract JSON from a text blob" step on our side.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolChoice(String type, String name) {}
