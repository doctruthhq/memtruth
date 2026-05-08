/**
 * DeepSeek Chat Completions wire-format records (request + response). NOT public API.
 *
 * <p>DeepSeek's API is OpenAI-API-compatible at the wire level, but per AGENTS.md §1
 * (decoupling) the records are intentionally <em>not</em> shared with the OpenAI provider.
 * If DeepSeek diverges in the future (e.g. {@code reasoning_content} for DeepSeek-R1) we
 * want to evolve this surface independently. The cost is ~70 lines of duplicate records;
 * the benefit is each provider's wire surface is independently versionable.
 *
 * <p>Anything under this package may be renamed, moved, or removed without a major
 * version bump. Downstream consumers must not depend on these types directly — use the
 * sealed {@link ai.doctruth.LlmProvider} surface instead.
 *
 * @hidden
 */
package ai.doctruth.internal.providers.deepseek.wire;
