package ai.doctruth.internal.providers.anthropic.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Anthropic prompt-cache control marker. Attached to a {@link SystemBlock} (or, in future
 * rounds, to specific content blocks) to opt that block into Anthropic's 5-minute ephemeral
 * prompt cache.
 *
 * <p>Anthropic exposes only one supported value at the time of writing — {@code "ephemeral"}
 * — but we keep the type as a record rather than a constant so the wire JSON stays
 * forward-compatible if Anthropic adds a longer TTL tier.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CacheControl(String type) {}
