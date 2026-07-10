package ai.doctruth.internal.providers.gemini.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Token-usage metadata returned by Gemini on every successful call.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsageMetadata(int promptTokenCount, int candidatesTokenCount, int totalTokenCount) {}
