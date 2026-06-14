package ai.doctruth;

import java.util.Objects;

/**
 * Runtime/resource observations captured for one parser benchmark case.
 *
 * @since 1.0.0
 */
public record ParserBenchmarkResources(double parserLatencyMs, double rssPeakMb, double modelCacheSizeMb) {

    public static final ParserBenchmarkResources ZERO = new ParserBenchmarkResources(0.0, 0.0, 0.0);

    public ParserBenchmarkResources {
        requireFiniteNonNegative("parserLatencyMs", parserLatencyMs);
        requireFiniteNonNegative("rssPeakMb", rssPeakMb);
        requireFiniteNonNegative("modelCacheSizeMb", modelCacheSizeMb);
    }

    private static void requireFiniteNonNegative(String name, double value) {
        Objects.requireNonNull(name, "name");
        if (value < 0.0 || Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
