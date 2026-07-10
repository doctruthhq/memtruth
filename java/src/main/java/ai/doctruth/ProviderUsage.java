package ai.doctruth;

import java.util.Objects;

/**
 * Token-usage and model-version data returned by an LLM provider on every successful call.
 * Carried into {@link Provenance} so audit logs can reconstruct cost and reproducibility.
 *
 * <p>Invariants (compact constructor):
 *
 * <ul>
 *   <li>{@code inputTokens >= 0}, {@code outputTokens >= 0}.
 *   <li>{@code modelVersion} non-null and non-blank.
 * </ul>
 *
 * @param inputTokens   prompt tokens billed by the provider.
 * @param outputTokens  completion tokens billed by the provider.
 * @param modelVersion  provider-reported model version (e.g. a date stamp or build id).
 * @since 0.1.0
 */
public record ProviderUsage(int inputTokens, int outputTokens, String modelVersion) {

    public ProviderUsage {
        Objects.requireNonNull(modelVersion, "modelVersion");
        if (inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens must be >= 0, got " + inputTokens);
        }
        if (outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens must be >= 0, got " + outputTokens);
        }
        if (modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion must not be blank");
        }
    }
}
