package ai.doctruth.internal.providers.anthropic.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Anthropic per-call token usage. Mirrors {@code response.usage} verbatim, including the
 * Phase 2 prompt-cache counters {@code cache_creation_input_tokens} (tokens written into a
 * fresh ephemeral cache entry on this call) and {@code cache_read_input_tokens} (tokens
 * served from a previously-warmed cache, billed at ~10% of the regular input rate).
 *
 * <p>Older response payloads (e.g. recorded fixtures pre-dating the cache-tier rollout) omit
 * the cache fields entirely. Jackson maps an absent JSON field to a primitive-int component
 * as 0 by default, which matches the "no cache activity" semantics we want — no special
 * configuration needed.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Usage(
        int input_tokens, int output_tokens, int cache_creation_input_tokens, int cache_read_input_tokens) {}
