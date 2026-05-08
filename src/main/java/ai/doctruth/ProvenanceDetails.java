package ai.doctruth;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Supplemental provenance metadata kept behind {@link Provenance} so the public provenance
 * record stays small while preserving retry, data-residency, and retention semantics.
 *
 * @param region      cloud / data-residency region the extraction was processed in.
 * @param retainUntil UTC timestamp after which this audit record may be destroyed.
 * @param retries     number of retries before success; {@code 0} means first attempt.
 * @since 0.1.0
 */
public record ProvenanceDetails(Optional<String> region, Optional<Instant> retainUntil, int retries) {

    public ProvenanceDetails {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(retainUntil, "retainUntil");
        if (retries < 0) {
            throw new IllegalArgumentException("retries must be >= 0, got " + retries);
        }
    }
}
