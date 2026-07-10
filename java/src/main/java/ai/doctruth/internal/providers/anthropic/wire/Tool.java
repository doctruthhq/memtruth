package ai.doctruth.internal.providers.anthropic.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One tool declaration in the Anthropic Messages-API request. The library declares a single
 * {@code "extract"} tool whose {@code input_schema} is the caller-supplied JSON Schema; the
 * model is then forced (via {@link ToolChoice}) to call exactly that tool, which gives
 * guaranteed-shape structured output without prompt-engineering acrobatics.
 *
 * <p>{@link JsonNode} (rather than a typed record) for {@code input_schema} so callers can
 * pass any Jackson-shaped schema straight through; Jackson re-serialises it on the wire
 * without copy or transformation.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Tool(String name, String description, JsonNode input_schema) {}
