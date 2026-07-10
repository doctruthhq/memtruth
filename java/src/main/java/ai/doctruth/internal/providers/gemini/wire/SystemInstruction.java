package ai.doctruth.internal.providers.gemini.wire;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code system_instruction} top-level field of a Gemini {@code generateContent}
 * request. Note the snake_case wire name.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SystemInstruction(List<Part> parts) {}
