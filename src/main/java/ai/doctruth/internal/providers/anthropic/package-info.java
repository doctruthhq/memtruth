/**
 * Anthropic Messages-API HTTP client + wire records. NOT public API.
 *
 * <p>This package owns the only Anthropic-specific HTTP / JSON code in the codebase. The
 * public {@link ai.doctruth.AnthropicProvider} delegates here and never sees Anthropic wire
 * types directly, per ADR 0003.
 */
package ai.doctruth.internal.providers.anthropic;
