/**
 * DeepSeek-specific HTTP plumbing for the {@link ai.doctruth.DeepSeekProvider}. NOT public
 * API.
 *
 * <p>DeepSeek's Chat Completions API is OpenAI-API-compatible at the wire level, but we
 * keep the wire records and HTTP client in a vendor-specific package per CONTRIBUTING.md §1
 * (decoupling) so the two providers can evolve independently.
 *
 * <p>Anything under this package may be renamed, moved, or removed without a major
 * version bump.
 */
package ai.doctruth.internal.providers.deepseek;
