package ai.doctruth;

import java.util.Optional;

/**
 * The Layer 2 backend abstraction: an LLM API client. Sealed; the only permitted
 * implementations are the four first-class providers Anthropic, OpenAI, Gemini, and
 * DeepSeek. New providers require a major version bump and a new {@code permits} entry.
 *
 * <p>Implementations must NOT leak vendor-SDK types through this interface (see ADR 0003).
 * All interaction with vendor APIs goes through {@code ai.doctruth.internal.providers.*}.
 *
 * <p>Each implementation is {@code non-sealed}, which lets test code anonymously subclass
 * to inject canned {@link ProviderResponse} values for orchestration tests, and lets
 * advanced users wrap a provider for behaviours like in-process recording or A/B routing.
 *
 * @since 0.1.0
 */
public sealed interface LlmProvider permits AnthropicProvider, OpenAiProvider, GeminiProvider, DeepSeekProvider {

    /**
     * Execute one extraction call against the provider.
     *
     * @param request the prepared request; never null.
     * @return the provider's raw JSON response and usage metadata.
     * @throws ProviderException on transport, schema, or upstream failure.
     */
    ProviderResponse complete(ProviderRequest request) throws ProviderException;

    /**
     * Logical lower-case name of the provider, e.g. {@code "anthropic"}. Recorded into
     * {@link Provenance#model()} on every {@link ExtractionResult}.
     */
    String name();

    /**
     * Optional region identifier for the provider's deployment, e.g. {@code "ap-southeast-2"}
     * for AWS Bedrock-AU or {@code "australiaeast"} for Azure-OpenAI-AU. Recorded into
     * {@link Provenance#region()} on every {@link ExtractionResult}; when the caller has
     * data-residency requirements, this is what proves them.
     *
     * <p>Default: empty (the four built-in providers point at their public global endpoints,
     * which return {@code Optional.empty()}). Region-aware provider wrappers can populate
     * this.
     */
    default Optional<String> region() {
        return Optional.empty();
    }
}
