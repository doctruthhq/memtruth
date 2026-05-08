/**
 * Per-vendor LLM HTTP clients (Anthropic, OpenAI, Gemini, DeepSeek). NOT public API.
 *
 * <p>Anything under this package may be renamed, moved, or removed without a major version
 * bump. Vendor wire shapes (request / response records) live under
 * {@code ai.doctruth.internal.providers.<vendor>.wire.*} and MUST NOT leak through the public
 * {@link ai.doctruth.LlmProvider} surface, per ADR 0003.
 *
 * @hidden
 */
package ai.doctruth.internal.providers;
