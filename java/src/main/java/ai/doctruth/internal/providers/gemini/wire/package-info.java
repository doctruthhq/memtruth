/**
 * Jackson wire records for the Google Gemini {@code generateContent} REST API. NOT public
 * API.
 *
 * <p>Field names mirror the Gemini wire format exactly (camelCase: {@code usageMetadata},
 * {@code generationConfig}, {@code responseMimeType}, plus the snake_case
 * {@code system_instruction}) so Jackson maps cleanly without {@code @JsonProperty}
 * annotations. Anything under this package may be renamed, moved, or removed without a
 * major version bump per ADR 0003 — vendor-specific knowledge stays here and never leaks
 * through {@link ai.doctruth.LlmProvider}.
 */
package ai.doctruth.internal.providers.gemini.wire;
