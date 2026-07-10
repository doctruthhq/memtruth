package ai.doctruth.internal.providers.gemini.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One part of a {@link Content} block. The Gemini API supports many part types
 * (text, inline data, function call, etc.); v0.1.0-alpha only emits / consumes text parts.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Part(String text) {}
