package ai.doctruth;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the library hands an {@link LlmProvider} on every call: the system prompt, the user
 * prompt (rendered from a {@link ParsedDocument} by the configured {@link ContextStrategy}),
 * the JSON Schema for the target type, and the per-call options.
 *
 * <p>Invariants (compact constructor):
 *
 * <ul>
 *   <li>{@code systemPrompt} non-null and non-blank.
 *   <li>{@code userPrompt} non-null. Empty string is allowed — some flows send a system-only
 *       prompt with the document data already encoded into the system message.
 *   <li>{@code responseSchema} non-null. The library always supplies a schema even if the
 *       caller did not request {@code withProvenance()} / {@code withConfidence()}.
 *   <li>{@code options} non-null.
 * </ul>
 *
 * @param systemPrompt    the instructions / role prompt.
 * @param userPrompt      the document content rendered for the LLM.
 * @param responseSchema  Jackson-shaped JSON Schema describing the target type.
 * @param options         per-call options (retries + timeout).
 * @since 0.1.0
 */
public record ProviderRequest(
        String systemPrompt, String userPrompt, JsonNode responseSchema, ProviderOptions options) {

    public ProviderRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userPrompt, "userPrompt");
        Objects.requireNonNull(responseSchema, "responseSchema");
        Objects.requireNonNull(options, "options");
        if (systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
    }
}
