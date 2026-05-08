package ai.doctruth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import ai.doctruth.internal.audit.ProvOExporter;
import ai.doctruth.spi.SignatureProvider;

/**
 * The output of {@code DocTruth.extract(...).run()}: the extracted value plus per-field
 * citations, per-field confidence scores, and run-level provenance.
 *
 * <p>Invariants (enforced by the compact constructor):
 *
 * <ul>
 *   <li>{@code value} is non-null.
 *   <li>{@code citations} and {@code confidence} are non-null (empty map allowed).
 *   <li>{@code provenance} is non-null.
 * </ul>
 *
 * <p>Both maps are defensively copied on construction and exposed as unmodifiable views;
 * mutating the original input map after construction does not affect the result, and
 * calling {@code put} / {@code remove} on the accessor's return value throws
 * {@link UnsupportedOperationException}.
 *
 * @param <T>        the extracted value's type (typically a Java {@code record}).
 * @param value      the extracted value.
 * @param citations  source citations keyed by field path (e.g. {@code "partyA"}, {@code "obligations[0].dueDate"}).
 * @param confidence confidence scores keyed by field path.
 * @param provenance run-level provenance (model, version, timestamps, retry count).
 * @since 0.1.0
 */
public record ExtractionResult<T>(
        T value, Map<String, Citation> citations, Map<String, Confidence> confidence, Provenance provenance) {

    public ExtractionResult {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(citations, "citations");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(provenance, "provenance");
        citations = Map.copyOf(citations);
        confidence = Map.copyOf(confidence);
    }

    /**
     * Render this result as a W3C PROV-O JSON-LD audit document — the format auditors and
     * compliance teams already know how to ingest. Pretty-printed; UTF-8 safe.
     *
     * @return JSON-LD string representation of the extraction's provenance graph.
     */
    public String toAuditJson() {
        return ProvOExporter.toJson(this);
    }

    /**
     * Write {@link #toAuditJson()} to {@code path}, creating parent directories if needed.
     *
     * @throws NullPointerException if {@code path} is null.
     * @throws IOException          if the file or its parents cannot be written.
     */
    public void toAuditJson(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, toAuditJson());
    }

    /**
     * Render this result as audit JSON and pipe through {@code signer} for tamper-evident
     * persistence. The default {@link SignatureProvider#IDENTITY} preserves the existing
     * {@link #toAuditJson()} bytes unchanged; custom implementations can wrap or sign the
     * JSON before it leaves the JVM.
     *
     * @throws NullPointerException if {@code signer} is null.
     */
    public String toAuditJson(SignatureProvider signer) {
        Objects.requireNonNull(signer, "signer");
        return signer.sign(toAuditJson());
    }

    /**
     * Write {@link #toAuditJson(SignatureProvider)} to {@code path}, creating parent
     * directories if needed.
     *
     * @throws NullPointerException if either argument is null.
     * @throws IOException          if the file or its parents cannot be written.
     */
    public void toAuditJson(Path path, SignatureProvider signer) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(signer, "signer");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, toAuditJson(signer));
    }
}
