package ai.doctruth;

import java.time.Instant;
import java.util.Objects;

/**
 * Extraction builder bound to a document, so the happy path can end with
 * {@link #run()} instead of passing the parsed document at the end.
 *
 * @param <T> extracted value type.
 * @since 0.2.0
 */
public final class DocumentExtractionBuilder<T> {

    private final ExtractionBuilder<T> delegate;
    private final ParsedDocument document;

    DocumentExtractionBuilder(ExtractionBuilder<T> delegate, ParsedDocument document) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.document = Objects.requireNonNull(document, "document");
    }

    public DocumentExtractionBuilder<T> withEvidence() {
        return new DocumentExtractionBuilder<>(
                delegate.withProvenance().withConfidence().withBitemporal(), document);
    }

    public DocumentExtractionBuilder<T> withMaxRetries(int n) {
        return new DocumentExtractionBuilder<>(delegate.withMaxRetries(n), document);
    }

    public DocumentExtractionBuilder<T> withContextStrategy(ContextStrategy strategy) {
        return new DocumentExtractionBuilder<>(delegate.withContextStrategy(strategy), document);
    }

    public DocumentExtractionBuilder<T> withSourcePublishedAt(Instant sourcePublishedAt) {
        return new DocumentExtractionBuilder<>(delegate.withSourcePublishedAt(sourcePublishedAt), document);
    }

    public ExtractionResult<T> run() throws ExtractionException {
        return delegate.run(document);
    }
}
