/**
 * OpenAI Chat-Completions HTTP client + wire records. NOT public API.
 *
 * <p>This package owns the only OpenAI-specific HTTP / JSON code in the codebase. The
 * public {@link ai.doctruth.OpenAiProvider} delegates here and never sees OpenAI wire
 * types directly, per ADR 0003.
 *
 * @hidden
 */
package ai.doctruth.internal.providers.openai;
