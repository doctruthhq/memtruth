package ai.doctruth.internal.providers.gemini.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One generation candidate in a Gemini response. v0.1.0-alpha only reads {@code content}
 * (and ignores e.g. {@code safetyRatings}, {@code citationMetadata}, etc. via the
 * {@link JsonIgnoreProperties} on the wire records).
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Candidate(Content content, String finishReason, int index) {}
