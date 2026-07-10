/**
 * Jackson-deserialisable Java records mirroring the OpenAI Chat Completions API request
 * and response shapes. NOT public API.
 *
 * <p>Field names use the wire JSON's snake_case verbatim (e.g. {@code response_format},
 * {@code prompt_tokens}, {@code finish_reason}) so Jackson maps each record component
 * without custom annotations. These records exist only to insulate
 * {@link ai.doctruth.OpenAiProvider} from vendor JSON drift, per ADR 0003.
 */
package ai.doctruth.internal.providers.openai.wire;
