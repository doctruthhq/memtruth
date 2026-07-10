/**
 * Shared JSON-over-HTTP plumbing for the four LLM providers (Anthropic, OpenAI, Gemini,
 * DeepSeek). NOT public API.
 *
 * <p>Anything under this package may be renamed, moved, or removed without a major
 * version bump. Downstream consumers must not depend on these types directly — use the
 * sealed {@link ai.doctruth.LlmProvider} surface instead.
 */
package ai.doctruth.internal.http;
