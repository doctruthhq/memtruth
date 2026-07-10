package ai.doctruth.internal.providers.gemini.wire;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code generateContent} request body. Field names match the Gemini wire format
 * verbatim ({@code system_instruction} is snake_case while {@code generationConfig} is
 * camelCase — Google's choice, not ours).
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerateContentRequest(
        SystemInstruction system_instruction, List<Content> contents, GenerationConfig generationConfig) {}
