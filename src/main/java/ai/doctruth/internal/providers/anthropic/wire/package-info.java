/**
 * Jackson-deserialisable Java records mirroring the Anthropic Messages-API request and
 * response shapes. NOT public API.
 *
 * <p>Field names use the wire JSON's snake_case verbatim (e.g. {@code max_tokens},
 * {@code input_tokens}) so Jackson maps each record component without custom annotations.
 * These records exist only to insulate {@link ai.doctruth.AnthropicProvider} from vendor JSON
 * drift, per ADR 0003.
 */
package ai.doctruth.internal.providers.anthropic.wire;
