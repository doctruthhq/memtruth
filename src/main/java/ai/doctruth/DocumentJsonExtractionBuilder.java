package ai.doctruth;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON Schema extraction builder bound to a document. This keeps the advanced
 * JSON-Schema path aligned with the SDK-first document flow.
 *
 * @since 0.2.0
 */
public final class DocumentJsonExtractionBuilder {

    private final JsonExtractionBuilder delegate;
    private final ParsedDocument document;

    DocumentJsonExtractionBuilder(JsonExtractionBuilder delegate, ParsedDocument document) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.document = Objects.requireNonNull(document, "document");
    }

    public DocumentJsonExtractionBuilder withEvidence() {
        return new DocumentJsonExtractionBuilder(
                delegate.withProvenance().withConfidence().withBitemporal(), document);
    }

    public DocumentJsonExtractionBuilder requireCitation(String fieldPath) {
        return new DocumentJsonExtractionBuilder(delegate.requireCitation(fieldPath), document);
    }

    public DocumentJsonExtractionBuilder withMaxRetries(int n) {
        return new DocumentJsonExtractionBuilder(delegate.withMaxRetries(n), document);
    }

    public DocumentJsonExtractionBuilder withContextStrategy(ContextStrategy strategy) {
        return new DocumentJsonExtractionBuilder(delegate.withContextStrategy(strategy), document);
    }

    public DocumentJsonExtractionBuilder withSourcePublishedAt(Instant sourcePublishedAt) {
        return new DocumentJsonExtractionBuilder(delegate.withSourcePublishedAt(sourcePublishedAt), document);
    }

    public ExtractionResult<JsonNode> runJson() throws ExtractionException {
        return delegate.runJson(document);
    }
}
