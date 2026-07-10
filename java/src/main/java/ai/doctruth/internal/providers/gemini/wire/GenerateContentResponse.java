package ai.doctruth.internal.providers.gemini.wire;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code generateContent} response body.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerateContentResponse(List<Candidate> candidates, UsageMetadata usageMetadata, String modelVersion) {}
