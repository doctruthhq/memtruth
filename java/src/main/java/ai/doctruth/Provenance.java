package ai.doctruth;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Bi-temporal provenance for an {@link ExtractionResult}: the model that produced it, when
 * the extraction ran, and (optionally) when the source document was authored, the region
 * the extraction was processed in, and the retention horizon of the audit record.
 *
 * <p>The bi-temporal pair {@code (extractedAt, sourcePublishedAt)} is the
 * differentiator vs. typical Java extraction pipelines: downstream auditors can answer
 * "did we extract this value before or after the source was updated?" without storing
 * extra metadata on every record.
 *
 * <p>The two compliance fields {@code region} and {@code retainUntil} close the
 * AU-bank-tier audit gap: when a regulator asks "where was this processed?" or "how long
 * are you keeping the audit record?", these fields give a verifiable answer per record.
 *
 * <p>Invariants (enforced by the compact constructor):
 *
 * <ul>
 *   <li>{@code model}, {@code modelVersion} are non-null and non-blank.
 *   <li>{@code extractedAt} is non-null.
 *   <li>{@code sourcePublishedAt} is a non-null {@link Optional}. Pass
 *       {@link Optional#empty()}, not {@code null}, when absent.
 *   <li>{@code details} is non-null and carries retry / residency / retention metadata.
 * </ul>
 *
 * @param model             logical model identifier, e.g. {@code "claude-sonnet-4-7"}.
 * @param modelVersion      provider-reported model version, e.g. a date stamp or build id.
 * @param extractedAt       UTC timestamp at which the extraction call completed.
 * @param sourcePublishedAt UTC timestamp at which the source document was published, if known.
 * @param details           retry / residency / retention metadata.
 * @since 0.1.0
 */
public record Provenance(
        String model,
        String modelVersion,
        Instant extractedAt,
        Optional<Instant> sourcePublishedAt,
        ProvenanceDetails details) {

    public Provenance(
            String model,
            String modelVersion,
            Instant extractedAt,
            Optional<Instant> sourcePublishedAt,
            Optional<String> region,
            Optional<Instant> retainUntil,
            int retries) {
        this(model, modelVersion, extractedAt, sourcePublishedAt, new ProvenanceDetails(region, retainUntil, retries));
    }

    public Provenance {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(extractedAt, "extractedAt");
        Objects.requireNonNull(sourcePublishedAt, "sourcePublishedAt");
        Objects.requireNonNull(details, "details");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion must not be blank");
        }
    }

    public Optional<String> region() {
        return details.region();
    }

    public Optional<Instant> retainUntil() {
        return details.retainUntil();
    }

    public int retries() {
        return details.retries();
    }
}
