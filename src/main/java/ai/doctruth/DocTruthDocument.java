package ai.doctruth;

import java.util.Objects;

/**
 * A parsed document bound to one provider. This is the short SDK path for users who
 * think in terms of "extract this schema from this document".
 *
 * @since 0.2.0
 */
public final class DocTruthDocument {

    private final LlmProvider provider;
    private final ParsedDocument document;

    DocTruthDocument(LlmProvider provider, ParsedDocument document) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.document = Objects.requireNonNull(document, "document");
    }

    public <T> DocumentExtractionBuilder<T> extract(String prompt, Class<T> type) {
        return new DocumentExtractionBuilder<>(DocTruth.from(provider).extract(prompt, type), document);
    }

    public DocumentJsonExtractionBuilder extractJson(String prompt, JsonSchema schema) {
        return new DocumentJsonExtractionBuilder(DocTruth.from(provider).extractJson(prompt, schema), document);
    }

    public TrustDocumentParserBuilder withParser(ParserPreset preset) {
        return new TrustDocumentParserBuilder(document, preset);
    }
}
