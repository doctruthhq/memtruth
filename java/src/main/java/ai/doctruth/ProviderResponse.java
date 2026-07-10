package ai.doctruth;

import java.util.Objects;

/**
 * What an {@link LlmProvider} returns on a successful call: the raw JSON the LLM produced
 * plus the per-call {@link ProviderUsage}. The library parses {@code rawJson} into the
 * caller's target type via Jackson; providers do not interpret it.
 *
 * <p>Invariants (compact constructor):
 *
 * <ul>
 *   <li>{@code rawJson} non-null and non-blank.
 *   <li>{@code usage} non-null.
 * </ul>
 *
 * @param rawJson the raw JSON body returned by the provider, untouched.
 * @param usage   token-usage + model-version metadata for the call.
 * @since 0.1.0
 */
public record ProviderResponse(String rawJson, ProviderUsage usage) {

    public ProviderResponse {
        Objects.requireNonNull(rawJson, "rawJson");
        Objects.requireNonNull(usage, "usage");
        if (rawJson.isBlank()) {
            throw new IllegalArgumentException("rawJson must not be blank");
        }
    }
}
