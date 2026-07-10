package ai.doctruth.internal.providers.anthropic.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Typed system-prompt block as accepted by the modern Anthropic Messages API. Replaces the
 * legacy {@code system: String} shape: the typed-block form is required for prompt caching
 * via {@link CacheControl} and is the only shape Anthropic guarantees forward-compat for.
 *
 * <p>{@code type} is always {@code "text"} for the v0.1.0-alpha system prompt; multimodal
 * system blocks are out of scope for this library.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SystemBlock(String type, String text, CacheControl cache_control) {}
