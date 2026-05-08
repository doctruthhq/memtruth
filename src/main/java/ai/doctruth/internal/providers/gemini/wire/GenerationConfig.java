package ai.doctruth.internal.providers.gemini.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code generationConfig} block of a Gemini request. It sets JSON MIME output and
 * carries the caller's response schema when available.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerationConfig(String responseMimeType, JsonNode responseSchema) {}
